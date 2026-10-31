import base64, json, urllib.request, urllib.parse, uuid
UAS = {
 "win-chrome": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
 "android-chrome": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
 "dalvik": "Dalvik/2.1.0 (Linux; U; Android 12; Smart TV Build/STT2.230203.001)",
 "cr-app": "Crunchyroll/3.70.0 Android/12 okhttp/4.12.0",
 "none": None,
}
def token(ua, extra={}):
    h = {"Authorization": "Basic " + base64.b64encode(b"cr_web:").decode(),
         "Content-Type": "application/x-www-form-urlencoded", "ETP-Anonymous-ID": str(uuid.uuid4()), **extra}
    if ua: h["User-Agent"] = ua
    r = urllib.request.Request("https://www.crunchyroll.com/auth/v1/token", data=b"grant_type=client_id", headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp: return resp.status, ""
    except urllib.error.HTTPError as e: return e.code, e.read().decode(errors="ignore")[:200].replace("\n"," ")
    except Exception as e: return -1, str(e)
for k, ua in UAS.items():
    print(k, *token(ua))
print("android+gzip", *token(UAS["android-chrome"], {"Accept-Encoding": "gzip", "Connection": "Keep-Alive"}))
print("none+gzip", *token(None, {"Accept-Encoding": "gzip", "Connection": "Keep-Alive"}))
