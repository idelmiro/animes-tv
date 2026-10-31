"""Gera data/episodes.json: código do episódio no Crunchyroll para cada anime em exibição.
Roda no GitHub Actions (a TV é bloqueada pelo antirrobô do Crunchyroll)."""
import base64, json, re, time, uuid, urllib.request, datetime

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

def http(url, data=None, headers=None):
    h = {"User-Agent": UA, **(headers or {})}
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=30) as r:
        return json.loads(r.read().decode())

Q = """query ($page: Int, $start: Int, $end: Int) { Page(page: $page, perPage: 50) { pageInfo { hasNextPage }
  airingSchedules(airingAt_greater: $start, airingAt_lesser: $end, sort: TIME) { episode media { externalLinks { site url } } } } }"""

now = int(time.time())
wanted = {}  # seriesId -> set(episodios)
page = 1
while page <= 30:
    body = json.dumps({"query": Q, "variables": {"page": page, "start": now - 9 * 86400, "end": now + 3 * 86400}}).encode()
    d = http("https://graphql.anilist.co", body, {"Content-Type": "application/json", "Accept": "application/json"})["data"]["Page"]
    for s in d["airingSchedules"]:
        for l in (s["media"] or {}).get("externalLinks") or []:
            if (l.get("site") or "").lower() == "crunchyroll":
                m = re.search(r"/series/([A-Z0-9]+)", l.get("url") or "")
                if m:
                    wanted.setdefault(m.group(1), set()).add(s["episode"])
    if not d["pageInfo"]["hasNextPage"]:
        break
    page += 1
    time.sleep(1)

tok = http("https://www.crunchyroll.com/auth/v1/token", b"grant_type=client_id", {
    "Authorization": "Basic " + base64.b64encode(b"cr_web:").decode(),
    "Content-Type": "application/x-www-form-urlencoded", "ETP-Anonymous-ID": str(uuid.uuid4())})["access_token"]
H = {"Authorization": "Bearer " + tok}

def ja(o):
    return o.get("audio_locale") == "ja-JP" or "ja-JP" in (o.get("audio_locales") or [])

def ep_id(e):
    for v in e.get("versions") or []:
        if v.get("audio_locale") == "ja-JP":
            return v["guid"]
    return e["id"]

out = {"episodes": {}, "latest": {}}
errors = 0
for sid, nums in wanted.items():
    try:
        seasons = http(f"https://www.crunchyroll.com/content/v2/cms/series/{sid}/seasons?locale=pt-BR", headers=H)["data"]
        seasons = [s for s in seasons if ja(s)] or seasons
        seasons.sort(key=lambda s: s.get("season_sequence_number") or s.get("season_number") or 0)
        found = set()
        for i, season in enumerate(reversed(seasons[-3:])):
            eps = http(f"https://www.crunchyroll.com/content/v2/cms/seasons/{season['id']}/episodes?locale=pt-BR", headers=H)["data"]
            if i == 0 and eps:
                e = max(eps, key=lambda e: e.get("sequence_number") or 0)
                out["latest"][sid] = {"id": ep_id(e), "slug": e.get("slug_title", ""), "n": e.get("episode_number")}
            for e in eps:
                n = e.get("episode_number")
                if n in nums and n not in found:
                    found.add(n)
                    out["episodes"][f"{sid}:{n}"] = {"id": ep_id(e), "slug": e.get("slug_title", "")}
            if found >= nums:
                break
        time.sleep(0.3)
    except Exception as ex:
        errors += 1
        print("erro", sid, ex)

out["updated"] = datetime.datetime.utcnow().isoformat() + "Z"
print(f"{len(wanted)} séries, {len(out['episodes'])} episódios, {errors} erros")
json.dump(out, open("episodes.json", "w"), ensure_ascii=False)
