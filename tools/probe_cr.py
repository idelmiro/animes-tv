import base64, json, urllib.request, urllib.parse, uuid

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

def req(url, data=None, headers={}):
    h = {"User-Agent": UA, **headers}
    r = urllib.request.Request(url, data=data, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except Exception as e:
        return -1, str(e)

token = None
for client in ["cr_web", "noaihdevm_6iyg0a8l0q", "t-kdgp2h8c3jub8fn0fq"]:
    basic = base64.b64encode(f"{client}:".encode()).decode()
    code, body = req("https://www.crunchyroll.com/auth/v1/token",
        data=urllib.parse.urlencode({"grant_type": "client_id"}).encode(),
        headers={"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded",
                 "ETP-Anonymous-ID": str(uuid.uuid4())})
    print("TOKEN", client, code, body[:150])
    if code == 200:
        token = json.loads(body)["access_token"]; break

if token:
    H = {"Authorization": "Bearer " + token}
    for sid in ["GT00384007", "GY5P48XEY"]:
        code, body = req(f"https://www.crunchyroll.com/content/v2/cms/series/{sid}/seasons?locale=pt-BR", headers=H)
        print("SEASONS", sid, code)
        if code != 200: print(body[:300]); continue
        seasons = json.loads(body)["data"]
        for s in seasons[-3:]:
            print("  season", s["id"], s.get("season_number"), s.get("audio_locale"), s.get("audio_locales"), s.get("is_subbed"), s.get("title"))
        s = seasons[-1]
        code, body = req(f"https://www.crunchyroll.com/content/v2/cms/seasons/{s['id']}/episodes?locale=pt-BR", headers=H)
        print("EPISODES", code)
        if code == 200:
            for e in json.loads(body)["data"][-3:]:
                print("  ep", e["id"], e.get("episode_number"), e.get("sequence_number"), e.get("slug_title"), e.get("audio_locale"), e.get("versions") and [(v["audio_locale"], v["guid"]) for v in e["versions"]][:3])
# rodada 2
