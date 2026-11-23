"""Simulação (temporária): consultas ao Crunchyroll por semana, método atual x novo."""
import base64, json, re, time, uuid, urllib.request, datetime as dt
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
def http(url, data=None, headers=None):
    h = {"User-Agent": UA, **(headers or {})}
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=30) as r:
        return json.loads(r.read().decode())
BRT = dt.timezone(dt.timedelta(hours=-3))
mon = dt.datetime(2026, 11, 23, tzinfo=BRT); week_start = int(mon.timestamp()); week_end = week_start + 7*86400
Q = """query ($page: Int, $start: Int, $end: Int) { Page(page: $page, perPage: 50) { pageInfo { hasNextPage }
  airingSchedules(airingAt_greater: $start, airingAt_lesser: $end, sort: TIME) { episode airingAt media { externalLinks { site url } } } } }"""
air = []  # (sid, ep, airingAt)
page = 1; pages = 0
while True:
    body = json.dumps({"query": Q, "variables": {"page": page, "start": week_start - 10*86400, "end": week_end + 4*86400}}).encode()
    d = http("https://graphql.anilist.co", body, {"Content-Type": "application/json", "Accept": "application/json"})["data"]["Page"]
    for s in d["airingSchedules"]:
        for l in (s["media"] or {}).get("externalLinks") or []:
            if (l.get("site") or "").lower() == "crunchyroll":
                m = re.search(r"/series/([A-Z0-9]+)", l.get("url") or "")
                if m: air.append((m.group(1), s["episode"], s["airingAt"])); break
    if not d["pageInfo"]["hasNextPage"]: break
    page += 1; time.sleep(1)
series = sorted({a[0] for a in air})
tok = http("https://www.crunchyroll.com/auth/v1/token", b"grant_type=client_id", {
    "Authorization": "Basic " + base64.b64encode(b"cr_web:").decode(),
    "Content-Type": "application/x-www-form-urlencoded", "ETP-Anonymous-ID": str(uuid.uuid4())})["access_token"]
H = {"Authorization": "Bearer " + tok}
nseas = {}
for sid in series:
    try:
        ss = http(f"https://www.crunchyroll.com/content/v2/cms/series/{sid}/seasons?locale=pt-BR", headers=H)["data"]
        ja = [s for s in ss if s.get("audio_locale") == "ja-JP" or "ja-JP" in (s.get("audio_locales") or [])] or ss
        nseas[sid] = max(1, min(3, len(ja)))
    except Exception: nseas[sid] = 1
    time.sleep(0.3)
OFF = 3600
def anilist_pages(t):  # páginas que o robô pede (janela -9d..+3d)
    n = sum(1 for a in air if t - 9*86400 < a[2] < t + 3*86400); return max(1, -(-n // 50))
def old_cost(t):
    w = {}
    for sid, ep, at in air:
        if t - 9*86400 < at < t + 3*86400: w.setdefault(sid, []).append(at)
    cr = 1
    for sid, ats in w.items():
        fut = any(at + OFF > t for at in ats)
        cr += 2 + (nseas[sid] if fut else 1)
    return cr, len(w)
def runs_every2h():
    t = week_start - (week_start % 7200) + 23*60
    while t < week_end:
        if t >= week_start: yield t
        t += 7200
def runs_new():
    for h in range(7*24):
        t = week_start + h*3600 + 23*60
        d = dt.datetime.fromtimestamp(t, BRT)
        wd = d.weekday()  # 0=seg
        if wd >= 5 or d.hour >= 15: yield t
def new_cost(times):
    pending = {(s, e) for s, e, at in air if week_start <= at + OFF}  # antes da semana: já achados
    done = set(); total = 0; empty = 0; per = []
    for t in times:
        due = {(s, e) for (s, e) in pending - done if [a for a in air if a[0]==s and a[1]==e][0][2] + OFF <= t}
        sids = {s for s, e in due}
        c = (1 + 2*len(sids)) if sids else 0
        if not sids: empty += 1
        done |= due; total += c; per.append(c)
    return total, empty, per
old_t = list(runs_every2h()); new_t = list(runs_new())
o = [old_cost(t) for t in old_t]
print("SERIES_CR", len(series), "EPISODIOS_SEMANA", sum(1 for a in air if week_start <= a[2] < week_end))
print("ATUAL: execucoes", len(old_t), "crunchyroll", sum(c for c, _ in o), "media/exec", round(sum(c for c,_ in o)/len(o)), "series/exec", round(sum(s for _,s in o)/len(o)), "anilist", sum(anilist_pages(t) for t in old_t))
for name, ts in [("NOVO_2H", old_t), ("NOVO_AGENDA", new_t)]:
    tot, empty, per = new_cost(ts)
    print(name, "execucoes", len(ts), "crunchyroll", tot, "vazias", empty, "max/exec", max(per), "anilist", sum(anilist_pages(t) for t in ts))
# distribuição de lançamentos (BRT) por hora
hist = {}
for s, e, at in air:
    if week_start <= at < week_end:
        d = dt.datetime.fromtimestamp(at + OFF, BRT); hist[(d.weekday(), d.hour)] = hist.get((d.weekday(), d.hour), 0) + 1
print("LANCAMENTOS", json.dumps({f"{k[0]}-{k[1]:02d}": v for k, v in sorted(hist.items())}))
