"""Gera data/episodes.json: código do episódio no Crunchyroll para cada anime em exibição.
Roda no GitHub Actions (a TV é bloqueada pelo antirrobô do Crunchyroll).

Incremental: reaproveita o episodes.json anterior (prev.json) e só consulta o Crunchyroll
para episódios que já foram exibidos no Japão e ainda não têm código."""
import base64, json, os, re, time, uuid, urllib.request, datetime

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

def http(url, data=None, headers=None):
    h = {"User-Agent": UA, **(headers or {})}
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=30) as r:
        return json.loads(r.read().decode())

Q = """query ($page: Int, $start: Int, $end: Int) { Page(page: $page, perPage: 50) { pageInfo { hasNextPage }
  airingSchedules(airingAt_greater: $start, airingAt_lesser: $end, sort: TIME) { episode airingAt media { externalLinks { site url } } } } }"""

# Episódio só é procurado depois de exibido no Japão + este atraso (legendado).
DELAY = 50 * 60
# Se o episódio não aparece no Crunchyroll em 1 dia, passa a tentar só a cada 6 horas.
RETRY_FAST = 24 * 3600
RETRY_SLOW = 6 * 3600

now = int(time.time())
wanted = {}  # seriesId -> {episodio: airingAt}
page = 1
while page <= 30:
    body = json.dumps({"query": Q, "variables": {"page": page, "start": now - 9 * 86400, "end": now + 3 * 86400}}).encode()
    d = http("https://graphql.anilist.co", body, {"Content-Type": "application/json", "Accept": "application/json"})["data"]["Page"]
    for s in d["airingSchedules"]:
        for l in (s["media"] or {}).get("externalLinks") or []:
            if (l.get("site") or "").lower() == "crunchyroll":
                m = re.search(r"/series/([A-Z0-9]+)", l.get("url") or "")
                if m:
                    wanted.setdefault(m.group(1), {})[s["episode"]] = s["airingAt"]
    if not d["pageInfo"]["hasNextPage"]:
        break
    page += 1
    time.sleep(1)

_H = None
def auth():
    global _H
    if _H is None:
        tok = http("https://www.crunchyroll.com/auth/v1/token", b"grant_type=client_id", {
            "Authorization": "Basic " + base64.b64encode(b"cr_web:").decode(),
            "Content-Type": "application/x-www-form-urlencoded", "ETP-Anonymous-ID": str(uuid.uuid4())})["access_token"]
        _H = {"Authorization": "Bearer " + tok}
    return _H


def ja(o):
    return o.get("audio_locale") == "ja-JP" or "ja-JP" in (o.get("audio_locales") or [])

def ep_id(e):
    for v in e.get("versions") or []:
        if v.get("audio_locale") == "ja-JP":
            return v["guid"]
    return e["id"]

try:
    prev = json.load(open("prev.json"))
except Exception:
    prev = {}
prev_eps = prev.get("episodes") or {}
prev_tries = prev.get("tries") or {}

out = {
    "episodes": {k: v for k, v in prev_eps.items() if k.split(":")[0] in wanted},
    "latest": {k: v for k, v in (prev.get("latest") or {}).items() if k in wanted},
    "images": {k: v for k, v in (prev.get("images") or {}).items() if k in wanted},
    "tries": {},
}
errors = 0
requests = 0

def pick_wide(series):
    imgs = (series.get("images") or {}).get("poster_wide") or []
    flat = [i for group in imgs for i in (group if isinstance(group, list) else [group])]
    if not flat:
        return None
    ok = [i for i in flat if i.get("width", 0) <= 1280] or flat
    return max(ok, key=lambda i: i.get("width", 0))["source"]

def due(sid, n, aired):
    """Episódio já exibido, sem código e na hora de tentar de novo."""
    key = f"{sid}:{n}"
    if key in out["episodes"] or aired + DELAY > now:
        return False
    last = prev_tries.get(key, 0)
    wait = 0 if now - aired < RETRY_FAST else RETRY_SLOW
    return now - last >= wait

def cr(url):
    global requests
    requests += 1
    return http(url, headers=auth())["data"]

for sid, eps in wanted.items():
    nums = {n for n, aired in eps.items() if due(sid, n, aired)}
    need_image = sid not in out["images"]
    if not nums and not need_image:
        continue
    try:
        if need_image:
            try:
                wide = pick_wide(cr(f"https://www.crunchyroll.com/content/v2/cms/series/{sid}?locale=pt-BR")[0])
                out["images"][sid] = wide or ""  # "" = série sem arte horizontal, não pede de novo
            except Exception as ex:
                print("imagem", sid, ex)
        if not nums:
            continue
        seasons = cr(f"https://www.crunchyroll.com/content/v2/cms/series/{sid}/seasons?locale=pt-BR")
        seasons = [s for s in seasons if ja(s)] or seasons
        seasons.sort(key=lambda s: s.get("season_sequence_number") or s.get("season_number") or 0)
        found = set()
        for i, season in enumerate(reversed(seasons[-3:])):
            eps_cr = cr(f"https://www.crunchyroll.com/content/v2/cms/seasons/{season['id']}/episodes?locale=pt-BR")
            if i == 0 and eps_cr:
                e = max(eps_cr, key=lambda e: e.get("sequence_number") or 0)
                out["latest"][sid] = {"id": ep_id(e), "slug": e.get("slug_title", ""), "n": e.get("episode_number")}
            for e in eps_cr:
                n = e.get("episode_number")
                if n in nums and n not in found:
                    found.add(n)
                    out["episodes"][f"{sid}:{n}"] = {"id": ep_id(e), "slug": e.get("slug_title", "")}
            if found >= nums:
                break
        for n in nums - found:
            out["tries"][f"{sid}:{n}"] = now
        time.sleep(0.3)
    except Exception as ex:
        errors += 1
        print("erro", sid, ex)

# Mantém o registro de tentativas de episódios que ainda não apareceram.
for k, t in prev_tries.items():
    sid, n = k.split(":")
    if k not in out["tries"] and k not in out["episodes"] and sid in wanted and int(n) in wanted[sid]:
        out["tries"][k] = t

same = all(out[k] == (prev.get(k) or {}) for k in ("episodes", "latest", "images", "tries"))
out["updated"] = prev.get("updated") if same and prev.get("updated") else datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
print(f"{len(wanted)} séries, {len(out['episodes'])} episódios, {len(out['images'])} imagens, "
      f"{requests} consultas ao Crunchyroll, {len(out['tries'])} aguardando, {errors} erros"
      + (" (sem mudanças)" if same else ""))
json.dump(out, open("episodes.json", "w"), ensure_ascii=False)
