// FermFrame - shows your fermentation graph (gravity + temperature) on a
// Geekmagic "Smart Weather Clock" (ESP8266 + 240x240 ST7789), all by itself.
// Made by its owner and Claude, 2026. (Called BrewScreen before 2.1.)
//
// Data comes straight from the cloud over Wi-Fi, no PC needed:
//   * Brewfather   (Brewfather user ID + API key)
//   * RAPT cloud   (RAPT account email + API secret)
//
// Setup: with no Wi-Fi saved, the clock opens the open Wi-Fi "FermFrame-Setup"
// and shows a QR code to join it; the setup page then pops up on the phone.
// Afterwards the settings live at http://fermframe.local/ (or the clock's IP),
// including "Look & feel": themes, colours, font, brightness, night dimming,
// graph type/range, rotating pages and the "Ready to keg?" badge, with a live
// preview.
//
// After a power cut: if the home Wi-Fi isn't back yet, the clock offers the
// setup network for 3 minutes, then restarts and tries again, forever.
//
// FERMFRAME_LITE build: only Wi-Fi setup, /save and /update - small enough for
// a brand-new clock's own update page. The installer replaces it with the full
// build straight away.

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ESP8266HTTPUpdateServer.h>
#ifndef FERMFRAME_LITE
#include <ESP8266mDNS.h>
#define EASY_ADDRESS "fermframe.local"
#endif
#include <DNSServer.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecureBearSSL.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include <qrcode.h>
#include <time.h>
#include <math.h>

#ifndef FERMFRAME_VERSION
#define FERMFRAME_VERSION "dev"
#endif
#ifdef FERMFRAME_LITE
#define FERMFRAME_BUILD "lite"
#else
#define FERMFRAME_BUILD "full"
#endif

// The address people can type: fermframe.local, or the IP in the lite build
String easyAddress() {
#ifdef EASY_ADDRESS
  return EASY_ADDRESS;
#else
  return WiFi.localIP().toString();
#endif
}

// ------------------------------------------------------------------ config

static const char* DEFAULT_TZ = "GMT0BST,M3.5.0/1,M10.5.0";   // UK

struct Config {
  // Wi-Fi + account
  char ssid[33]   = "";
  char pass[65]   = "";
  char source[12] = "brewfather"; // "brewfather" or "rapt"
  char user[72]   = "";
  char secret[112]= "";
  char device[40] = "";       // optional: part of the batch / pill name to show
  char unit[4]    = "C";
  int  refreshMin = 5;
  char tz[48]     = "GMT0BST,M3.5.0/1,M10.5.0";
  // Look & feel
  char theme[10]  = "beer";
  char cBg[8]     = "#000000";
  char cText[8]   = "#FFFFFF";
  char cSg[8]     = "#FFCC00";
  char cTemp[8]   = "#5BBEFF";
  char cGrid[8]   = "#2A2A2A";
  char cDim[8]    = "#8C8C8C";
  char font[10]   = "digital";  // digital | modern | classic | mono
  int  bright     = 100;        // 5..100 %
  bool night      = false;
  char nFrom[6]   = "22:00";
  char nTo[6]     = "07:00";
  int  nLevel     = 0;          // brightness at night, 0 = screen off
  // Graph
  char graph[6]   = "line";     // line | area | dots | bars
  char plot[6]    = "both";     // both | sg | temp
  int  rangeH     = 168;        // 24, 72, 168, 336 hours; 0 = whole batch
  // Pages
  bool pgGraph    = true;
  bool pgBig      = false;
  bool pgClock    = false;
  bool pgBrews    = false;
  int  pageSecs   = 10;
  bool keg        = true;       // "Ready to keg?" badge
} cfg;

static const char* CONFIG_FILE = "/config.json";
static const char* AP_NAME     = "FermFrame-Setup";
static const char* HOSTNAME    = "fermframe";
static const unsigned long SETUP_RETRY_MS = 3UL * 60UL * 1000UL;  // power-cut retry

bool isBrewfather() { return cfg.source[0] != 'r' && cfg.source[0] != 'R'; }
bool isFahrenheit() { return cfg.unit[0] == 'F' || cfg.unit[0] == 'f'; }
bool hasAccount()   { return cfg.user[0] && cfg.secret[0]; }

static void copyStr(JsonDocument& d, const char* key, char* dst, size_t n, const char* def) {
  strlcpy(dst, d[key] | def, n);
}

void loadConfig() {
  File f = LittleFS.open(CONFIG_FILE, "r");
  if (!f) return;
  JsonDocument d;
  if (deserializeJson(d, f) == DeserializationError::Ok) {
    copyStr(d, "ssid", cfg.ssid, sizeof(cfg.ssid), "");
    copyStr(d, "pass", cfg.pass, sizeof(cfg.pass), "");
    copyStr(d, "source", cfg.source, sizeof(cfg.source), "brewfather");
    copyStr(d, "user", cfg.user, sizeof(cfg.user), "");
    copyStr(d, "secret", cfg.secret, sizeof(cfg.secret), "");
    copyStr(d, "device", cfg.device, sizeof(cfg.device), "");
    copyStr(d, "unit", cfg.unit, sizeof(cfg.unit), "C");
    copyStr(d, "tz", cfg.tz, sizeof(cfg.tz), DEFAULT_TZ);
    cfg.refreshMin = constrain((int)(d["refresh"] | 5), 1, 120);
    copyStr(d, "theme", cfg.theme, sizeof(cfg.theme), "beer");
    copyStr(d, "cBg", cfg.cBg, sizeof(cfg.cBg), "#000000");
    copyStr(d, "cText", cfg.cText, sizeof(cfg.cText), "#FFFFFF");
    copyStr(d, "cSg", cfg.cSg, sizeof(cfg.cSg), "#FFCC00");
    copyStr(d, "cTemp", cfg.cTemp, sizeof(cfg.cTemp), "#5BBEFF");
    copyStr(d, "cGrid", cfg.cGrid, sizeof(cfg.cGrid), "#2A2A2A");
    copyStr(d, "cDim", cfg.cDim, sizeof(cfg.cDim), "#8C8C8C");
    copyStr(d, "font", cfg.font, sizeof(cfg.font), "digital");
    cfg.bright = constrain((int)(d["bright"] | 100), 5, 100);
    cfg.night  = d["night"] | false;
    copyStr(d, "nFrom", cfg.nFrom, sizeof(cfg.nFrom), "22:00");
    copyStr(d, "nTo", cfg.nTo, sizeof(cfg.nTo), "07:00");
    cfg.nLevel = constrain((int)(d["nLevel"] | 0), 0, 100);
    copyStr(d, "graph", cfg.graph, sizeof(cfg.graph), "line");
    copyStr(d, "plot", cfg.plot, sizeof(cfg.plot), "both");
    // older versions stored "days"
    int range = d["range"].isNull() ? (int)(d["days"] | 7) * 24 : (int)(d["range"] | 168);
    cfg.rangeH = (range == 0) ? 0 : constrain(range, 24, 60 * 24);
    cfg.pgGraph = d["pgGraph"] | true;
    cfg.pgBig   = d["pgBig"] | false;
    cfg.pgClock = d["pgClock"] | false;
    cfg.pgBrews = d["pgBrews"] | false;
    cfg.pageSecs = constrain((int)(d["pageSecs"] | 10), 3, 600);
    cfg.keg     = d["keg"] | true;
  }
  f.close();
}

void saveConfig() {
  JsonDocument d;
  d["ssid"] = cfg.ssid; d["pass"] = cfg.pass; d["source"] = cfg.source; d["user"] = cfg.user;
  d["secret"] = cfg.secret; d["device"] = cfg.device; d["unit"] = cfg.unit; d["tz"] = cfg.tz;
  d["refresh"] = cfg.refreshMin;
  d["theme"] = cfg.theme; d["cBg"] = cfg.cBg; d["cText"] = cfg.cText; d["cSg"] = cfg.cSg;
  d["cTemp"] = cfg.cTemp; d["cGrid"] = cfg.cGrid; d["cDim"] = cfg.cDim; d["font"] = cfg.font;
  d["bright"] = cfg.bright; d["night"] = cfg.night; d["nFrom"] = cfg.nFrom; d["nTo"] = cfg.nTo;
  d["nLevel"] = cfg.nLevel; d["graph"] = cfg.graph; d["plot"] = cfg.plot; d["range"] = cfg.rangeH;
  d["pgGraph"] = cfg.pgGraph; d["pgBig"] = cfg.pgBig; d["pgClock"] = cfg.pgClock; d["pgBrews"] = cfg.pgBrews;
  d["pageSecs"] = cfg.pageSecs; d["keg"] = cfg.keg;
  File f = LittleFS.open(CONFIG_FILE, "w");
  if (f) { serializeJson(d, f); f.close(); }
}

// ------------------------------------------------------------------ globals

TFT_eSPI tft;
ESP8266WebServer server(80);
ESP8266HTTPUpdateServer updater;
DNSServer dns;

bool setupMode = false;
unsigned long setupSince = 0, lastWebHit = 0, restartAt = 0;
String netOptions;

bool needRefresh = true;
unsigned long lastFetch = 0, lastMinute = 0, pageSince = 0;
int pageIndex = 0;

String raptToken;
unsigned long raptTokenExpires = 0;

// Colours (RGB565), set from the config
uint16_t C_BG, C_TEXT, C_SG, C_TEMP, C_GRID, C_DIM;
const uint16_t C_ERR = 0xF9A6;   // soft red
const uint16_t C_OK  = 0x5FEA;   // green

// Graph data: readings are averaged into time buckets (one bucket = 2 pixels)
const int GX0 = 32, GX1 = 208;
const int GY0 = 98, GY1 = 222;
const int NB  = (GX1 - GX0) / 2;
float  sumSg[NB], sumT[NB];
uint16_t nSg[NB], nT[NB];
time_t winStart = 0, winEnd = 0, fetchStart = 0;

struct Summary {
  char   name[48] = "";
  float  sg = NAN, temp = NAN, og = NAN;
  int    battery = -1;
  time_t lastT = 0, firstT = 0, batchStart = 0;
  float  sg72 = NAN; long sg72gap = 0x7fffffff;   // reading closest to 3 days ago
  bool   ready = false;
  int    count = 0;
} cur;

// Other fermenting brews (for the rotating "brew" pages)
struct Brew { char name[32]; float sg, temp; time_t lastT; };
Brew others[4];
int nOthers = 0;

String lastError;   // short, fits the status line
String lastHint;    // what to do about it

// ------------------------------------------------------------------ helpers

static time_t toEpoch(int y, int m, int d, int hh, int mi, int ss) {
  y -= m <= 2;
  long era = (y >= 0 ? y : y - 399) / 400;
  unsigned yoe = (unsigned)(y - era * 400);
  unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  long days = era * 146097 + (long)doe - 719468;
  return (time_t)days * 86400 + hh * 3600 + mi * 60 + ss;
}

static int takeInt(const char*& p, int digits) {
  int v = 0;
  for (int i = 0; i < digits; i++) {
    if (*p < '0' || *p > '9') return -1;
    v = v * 10 + (*p++ - '0');
  }
  return v;
}

// "2026-11-14T10:23:45.123+00:00" / "...Z" / no zone (assumed UTC)
static time_t parseIso(const char* s) {
  if (!s || strlen(s) < 19) return 0;
  const char* p = s;
  int y = takeInt(p, 4); p++;
  int mo = takeInt(p, 2); p++;
  int d = takeInt(p, 2); p++;
  int h = takeInt(p, 2); p++;
  int mi = takeInt(p, 2); p++;
  int se = takeInt(p, 2);
  if (y < 0 || mo < 0 || d < 0 || h < 0 || mi < 0 || se < 0) return 0;
  time_t t = toEpoch(y, mo, d, h, mi, se);
  while (*p && *p != '+' && *p != '-' && *p != 'Z') p++;
  if (*p == '+' || *p == '-') {
    char sign = *p++;
    int oh = takeInt(p, 2); if (*p == ':') p++;
    int om = takeInt(p, 2);
    long off = (oh > 0 ? oh : 0) * 3600L + (om > 0 ? om : 0) * 60L;
    t += (sign == '+') ? -off : off;
  }
  return t;
}

static void isoFromEpoch(time_t t, char* out, size_t n) {
  struct tm g; gmtime_r(&t, &g);
  snprintf(out, n, "%04d-%02d-%02dT%02d:%02d:%02dZ", (g.tm_year + 1900) % 10000, (g.tm_mon + 1) % 100,
           g.tm_mday % 100, g.tm_hour % 100, g.tm_min % 100, g.tm_sec % 100);
}

static String urlEncode(const char* s) {
  String o; const char* hex = "0123456789ABCDEF";
  for (; *s; s++) {
    char c = *s;
    if (isalnum((unsigned char)c) || c == '-' || c == '_' || c == '.' || c == '~') o += c;
    else { o += '%'; o += hex[(c >> 4) & 15]; o += hex[c & 15]; }
  }
  return o;
}

static String html(const char* s) {
  String o;
  for (; *s; s++) {
    switch (*s) {
      case '&': o += F("&amp;"); break;
      case '<': o += F("&lt;"); break;
      case '>': o += F("&gt;"); break;
      case '"': o += F("&quot;"); break;
      default:  o += *s;
    }
  }
  return o;
}

static bool containsIgnoreCase(const char* hay, const char* needle) {
  if (!needle || !*needle) return true;
  if (!hay) return false;
  String h(hay), n(needle); h.toLowerCase(); n.toLowerCase();
  return h.indexOf(n) >= 0;
}

static float showTemp(float c) { return isFahrenheit() ? c * 9.0f / 5.0f + 32.0f : c; }
static String fmt(float v, int decimals) { return String(v, (unsigned int)decimals); }
String settingsUrl() { return "http://" + WiFi.localIP().toString() + "/"; }
void setProblem(const String& shortText, const String& hint) { lastError = shortText; lastHint = hint; }

static uint16_t hex565(const char* h, uint16_t def) {
  if (!h || h[0] != '#' || strlen(h) != 7) return def;
  long v = strtol(h + 1, nullptr, 16);
  return tft.color565((v >> 16) & 255, (v >> 8) & 255, v & 255);
}

void applyColours() {
  C_BG   = hex565(cfg.cBg, TFT_BLACK);
  C_TEXT = hex565(cfg.cText, TFT_WHITE);
  C_SG   = hex565(cfg.cSg, 0xFE60);
  C_TEMP = hex565(cfg.cTemp, 0x5DDF);
  C_GRID = hex565(cfg.cGrid, 0x2945);
  C_DIM  = hex565(cfg.cDim, 0x8C71);
}

// "HH:MM" -> minutes after midnight
static int hhmm(const char* s) {
  int h = atoi(s); const char* c = strchr(s, ':');
  int m = c ? atoi(c + 1) : 0;
  return constrain(h, 0, 23) * 60 + constrain(m, 0, 59);
}

// ------------------------------------------------------------------ backlight

int currentLevel = -1;
void setBacklight(int pct) {
  pct = constrain(pct, 0, 100);
  if (pct == currentLevel) return;
  currentLevel = pct;
  bool activeLow = (BACKLIGHT_ON == LOW);
  if (pct >= 100) { analogWrite(BACKLIGHT_PIN, 0); digitalWrite(BACKLIGHT_PIN, BACKLIGHT_ON); return; }
  if (pct == 0)   { analogWrite(BACKLIGHT_PIN, 0); digitalWrite(BACKLIGHT_PIN, activeLow ? HIGH : LOW); return; }
  int duty = pct * 255 / 100;
  analogWrite(BACKLIGHT_PIN, activeLow ? 255 - duty : duty);
}

bool isNightNow() {
  if (!cfg.night) return false;
  time_t now = time(nullptr);
  if (now < 1700000000) return false;
  struct tm lt; localtime_r(&now, &lt);
  int m = lt.tm_hour * 60 + lt.tm_min, a = hhmm(cfg.nFrom), b = hhmm(cfg.nTo);
  return a <= b ? (m >= a && m < b) : (m >= a || m < b);
}

void updateBacklight() { setBacklight(isNightNow() ? cfg.nLevel : cfg.bright); }

// ------------------------------------------------------------------ data collection

void resetData(time_t start) {
  for (int i = 0; i < NB; i++) { sumSg[i] = sumT[i] = 0; nSg[i] = nT[i] = 0; }
  cur = Summary();
  nOthers = 0;
  time_t now = time(nullptr);
  winEnd = now;
  winStart = start;
  fetchStart = min(winStart, now - (time_t)4 * 86400);   // 4 days for "Ready to keg?"
}

time_t rangeStart() {
  time_t now = time(nullptr);
  return now - (time_t)(cfg.rangeH > 0 ? cfg.rangeH : 14 * 24) * 3600;
}

void addReading(time_t t, float sg, float tempC) {
  if (t < fetchStart || t > winEnd + 3600) return;
  if (!isnan(sg) && sg > 2.0f) sg /= 1000.0f;            // RAPT sends 1012.3 style values
  if (!isnan(sg) && (sg < 0.95f || sg > 1.20f)) sg = NAN;
  if (!isnan(tempC) && (tempC < -20 || tempC > 60)) tempC = NAN;
  if (isnan(sg) && isnan(tempC)) return;

  if (t >= winStart) {
    long span = (long)(winEnd - winStart);
    int i = (int)((long long)(t - winStart) * NB / (span > 0 ? span : 1));
    i = constrain(i, 0, NB - 1);
    if (!isnan(sg))    { sumSg[i] += sg;    nSg[i]++; }
    if (!isnan(tempC)) { sumT[i]  += tempC; nT[i]++; }
  }
  if (!isnan(sg) && (isnan(cur.og) || sg > cur.og)) cur.og = sg;
  if (!isnan(sg)) {
    long gap = labs((long)(t - (winEnd - 3 * 86400)));
    if (gap < cur.sg72gap) { cur.sg72gap = gap; cur.sg72 = sg; }
  }
  if (t >= cur.lastT) {
    cur.lastT = t;
    if (!isnan(sg)) cur.sg = sg;
    if (!isnan(tempC)) cur.temp = tempC;
  }
  if (cur.firstT == 0 || t < cur.firstT) cur.firstT = t;
  cur.count++;
}

// Gravity hasn't moved for 3 days and fermentation clearly happened
void decideReady() {
  cur.ready = !isnan(cur.sg) && !isnan(cur.sg72) && cur.sg72gap < 12L * 3600 &&
              (long)(winEnd - cur.lastT) < 12L * 3600 &&
              fabsf(cur.sg - cur.sg72) <= 0.0011f &&
              (isnan(cur.og) || cur.og - cur.sg >= 0.010f);
}

int httpsRequest(BearSSL::WiFiClientSecure& client, HTTPClient& http, const String& url,
                 const char* auth, const char* basicUser, const char* basicPass,
                 const String* formBody) {
  client.setInsecure();
  client.setBufferSizes(16384, 512);
  http.setTimeout(20000);
  http.useHTTP10(true);   // no chunked encoding, so we can stream-parse the reply
  if (!http.begin(client, url)) return -1;
  http.addHeader("Accept", "application/json");
  if (auth) http.addHeader("Authorization", auth);
  if (basicUser) http.setAuthorization(basicUser, basicPass);
  if (formBody) {
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    return http.POST(*formBody);
  }
  return http.GET();
}

void httpProblem(const char* service, int code) {
  if (code < 0) setProblem("Can't reach the internet", "Check your router. Trying again in a minute.");
  else setProblem(String(service) + " problem (code " + code + ")", "Not your fault - trying again in a minute.");
}

template <typename F>
bool forEachInArray(Stream& s, JsonDocument& filter, F fn) {
  if (!s.find("[")) return false;
  do {
    JsonDocument item;
    if (deserializeJson(item, s, DeserializationOption::Filter(filter))) break;
    fn(item);
  } while (s.findUntil(",", "]"));
  return true;
}

// ---- RAPT ----

bool raptLogin() {
  BearSSL::WiFiClientSecure client; HTTPClient http;
  String body = "client_id=rapt-user&grant_type=password&username=" + urlEncode(cfg.user) +
                "&password=" + urlEncode(cfg.secret);
  int code = httpsRequest(client, http, "https://id.rapt.io/connect/token", nullptr, nullptr, nullptr, &body);
  if (code != 200) {
    if (code == 400 || code == 401) setProblem("RAPT: wrong email or API secret",
        "Use your RAPT email and an API secret (not your password). Scan to fix.");
    else httpProblem("RAPT", code);
    http.end(); return false;
  }
  JsonDocument filter; filter["access_token"] = true; filter["expires_in"] = true;
  JsonDocument d;
  deserializeJson(d, http.getStream(), DeserializationOption::Filter(filter));
  http.end();
  raptToken = d["access_token"] | "";
  long ttl = d["expires_in"] | 3600;
  raptTokenExpires = millis() + (unsigned long)(ttl - 120) * 1000UL;
  if (!raptToken.length()) { httpProblem("RAPT", 200); return false; }
  return true;
}

bool raptFetch() {
  if (!raptToken.length() || (long)(millis() - raptTokenExpires) > 0) {
    if (!raptLogin()) return false;
  }
  String auth = "Bearer " + raptToken;
  resetData(rangeStart());

  String pillId;
  {
    BearSSL::WiFiClientSecure client; HTTPClient http;
    int code = httpsRequest(client, http, "https://api.rapt.io/api/Hydrometers/GetHydrometers",
                            auth.c_str(), nullptr, nullptr, nullptr);
    if (code == 401) { raptToken = ""; http.end(); httpProblem("RAPT", code); return false; }
    if (code != 200) { httpProblem("RAPT", code); http.end(); return false; }
    JsonDocument filter;
    filter["id"] = true; filter["name"] = true; filter["battery"] = true;
    filter["gravity"] = true; filter["temperature"] = true; filter["lastActivityTime"] = true;
    forEachInArray(http.getStream(), filter, [&](JsonDocument& p) {
      const char* name = p["name"] | "RAPT Pill";
      if (!pillId.length() && containsIgnoreCase(name, cfg.device)) {
        pillId = p["id"] | "";
        strlcpy(cur.name, name, sizeof(cur.name));
        if (!p["battery"].isNull()) cur.battery = (int)roundf(p["battery"].as<float>());
      } else if (nOthers < 4) {
        Brew& b = others[nOthers++];
        strlcpy(b.name, name, sizeof(b.name));
        b.sg = p["gravity"] | NAN; if (!isnan(b.sg) && b.sg > 2.0f) b.sg /= 1000.0f;
        b.temp = p["temperature"] | NAN;
        b.lastT = parseIso(p["lastActivityTime"] | (const char*)nullptr);
      }
    });
    http.end();
  }
  if (!pillId.length()) {
    if (cfg.device[0]) setProblem("No pill called '" + String(cfg.device) + "'", "Check the name, or clear it in the settings. Scan to fix.");
    else setProblem("No RAPT Pill on this account", "Check the pill shows up in the RAPT app.");
    return false;
  }

  char from[24], to[24];
  isoFromEpoch(fetchStart, from, sizeof(from));
  isoFromEpoch(winEnd + 3600, to, sizeof(to));
  String url = "https://api.rapt.io/api/Hydrometers/GetTelemetry?hydrometerId=" + pillId +
               "&startDate=" + from + "&endDate=" + to;
  BearSSL::WiFiClientSecure client; HTTPClient http;
  int code = httpsRequest(client, http, url, auth.c_str(), nullptr, nullptr, nullptr);
  if (code != 200) { httpProblem("RAPT", code); http.end(); return false; }
  JsonDocument filter;
  filter["createdOn"] = true; filter["gravity"] = true; filter["temperature"] = true; filter["battery"] = true;
  int battery = -1; time_t batteryT = 0;
  forEachInArray(http.getStream(), filter, [&](JsonDocument& r) {
    time_t t = parseIso(r["createdOn"] | (const char*)nullptr);
    if (!t) return;
    addReading(t, r["gravity"] | NAN, r["temperature"] | NAN);
    if (!r["battery"].isNull() && t >= batteryT) { batteryT = t; battery = (int)roundf(r["battery"].as<float>()); }
    yield();
  });
  http.end();
  if (battery >= 0) cur.battery = battery;
  return true;
}

// ---- Brewfather ----

bool brewfatherFetch() {
  struct BatchInfo { char id[48]; char name[32]; time_t start; float og; };   // Brewfather ids are 28+ chars
  static BatchInfo list[5];
  int nList = 0, mainIdx = -1;
  {
    BearSSL::WiFiClientSecure client; HTTPClient http;
    int code = httpsRequest(client, http,
                            "https://api.brewfather.app/v2/batches?status=Fermenting&limit=50"
                            "&include=recipe.name,fermentationStartDate,brewDate,measuredOg",
                            nullptr, cfg.user, cfg.secret, nullptr);
    if (code == 401 || code == 403) {
      setProblem("Brewfather: wrong ID or API key",
                 "Brewfather > Settings > API: copy the User ID and a key with 'Read batches'. Scan to fix.");
      http.end(); return false;
    }
    if (code != 200) { httpProblem("Brewfather", code); http.end(); return false; }
    JsonDocument filter;
    filter["_id"] = true; filter["name"] = true; filter["recipe"]["name"] = true;
    filter["fermentationStartDate"] = true; filter["brewDate"] = true; filter["measuredOg"] = true;
    forEachInArray(http.getStream(), filter, [&](JsonDocument& b) {
      if (nList >= 5) return;
      const char* recipe = b["recipe"]["name"] | (const char*)nullptr;
      const char* name   = b["name"] | "Batch";
      BatchInfo& bi = list[nList];
      strlcpy(bi.id, b["_id"] | "", sizeof(bi.id));
      strlcpy(bi.name, recipe ? recipe : name, sizeof(bi.name));
      double startMs = b["fermentationStartDate"] | 0.0;
      if (startMs <= 0) startMs = b["brewDate"] | 0.0;
      bi.start = (time_t)(startMs / 1000.0);
      bi.og = b["measuredOg"] | NAN;
      if (mainIdx < 0 && (containsIgnoreCase(name, cfg.device) || containsIgnoreCase(recipe, cfg.device))) mainIdx = nList;
      nList++;
    });
    http.end();
  }
  if (mainIdx < 0) {
    if (cfg.device[0]) setProblem("No batch called '" + String(cfg.device) + "'", "Check the name, or clear it in the settings. Scan to fix.");
    else setProblem("No Fermenting batch in Brewfather", "In Brewfather, set your batch's status to Fermenting.");
    return false;
  }
  BatchInfo& mb = list[mainIdx];
  time_t now = time(nullptr);
  time_t start = rangeStart();
  if (cfg.rangeH == 0 && mb.start > 1500000000 && mb.start < now - 3600) start = mb.start;
  resetData(start);
  strlcpy(cur.name, mb.name, sizeof(cur.name));
  if (mb.start > 1500000000) cur.batchStart = mb.start;

  {
    BearSSL::WiFiClientSecure client; HTTPClient http;
    int code = httpsRequest(client, http, "https://api.brewfather.app/v2/batches/" + String(mb.id) + "/readings",
                            nullptr, cfg.user, cfg.secret, nullptr);
    if (code != 200) { httpProblem("Brewfather", code); http.end(); return false; }
    JsonDocument filter;
    filter["time"] = true; filter["sg"] = true; filter["temp"] = true; filter["battery"] = true;
    int battery = -1; time_t batteryT = 0;
    forEachInArray(http.getStream(), filter, [&](JsonDocument& r) {
      double ms = r["time"] | 0.0;
      if (ms <= 0) return;
      time_t t = (time_t)(ms / 1000.0);
      addReading(t, r["sg"] | NAN, r["temp"] | NAN);
      if (!r["battery"].isNull() && t >= batteryT) { batteryT = t; battery = (int)roundf(r["battery"].as<float>()); }
      yield();
    });
    http.end();
    if (battery >= 0) cur.battery = battery;
  }
  if (!isnan(mb.og) && mb.og > 1.0f && mb.og < 1.2f) cur.og = mb.og;

  // Latest reading of the other fermenting batches (only if those pages are on)
  if (cfg.pgBrews) {
    for (int i = 0; i < nList && nOthers < 4; i++) {
      if (i == mainIdx) continue;
      BearSSL::WiFiClientSecure client; HTTPClient http;
      int code = httpsRequest(client, http, "https://api.brewfather.app/v2/batches/" + String(list[i].id) + "/readings/last",
                              nullptr, cfg.user, cfg.secret, nullptr);
      if (code == 200) {
        JsonDocument filter; filter["time"] = true; filter["sg"] = true; filter["temp"] = true;
        JsonDocument r;
        if (!deserializeJson(r, http.getStream(), DeserializationOption::Filter(filter))) {
          Brew& b = others[nOthers++];
          strlcpy(b.name, list[i].name, sizeof(b.name));
          b.sg = r["sg"] | NAN; b.temp = r["temp"] | NAN;
          b.lastT = (time_t)((r["time"] | 0.0) / 1000.0);
        }
      }
      http.end();
    }
  }
  return true;
}

// ------------------------------------------------------------------ drawing

enum TextSize { MID, BIG };

const GFXfont* styleFont(TextSize s) {
  if (!strcmp(cfg.font, "modern"))  return s == BIG ? &FreeSansBold24pt7b  : &FreeSansBold18pt7b;
  if (!strcmp(cfg.font, "classic")) return s == BIG ? &FreeSerifBold24pt7b : &FreeSerifBold18pt7b;
  if (!strcmp(cfg.font, "mono"))    return s == BIG ? &FreeMonoBold24pt7b  : &FreeMonoBold18pt7b;
  return nullptr;   // digital: built-in 7-segment (BIG) and font 4 (MID)
}

// Draws text in the chosen font style
void drawStyled(const String& s, int x, int y, uint8_t datum, uint16_t col, TextSize sz) {
  tft.setTextDatum(datum);
  const GFXfont* f = styleFont(sz);
  if (f) {
    tft.setFreeFont(f); tft.setTextColor(col);
    tft.drawString(s, x, y);
    tft.setTextFont(1);
  } else {
    tft.setTextColor(col, C_BG);
    tft.drawString(s, x, y, sz == BIG ? 7 : 4);
  }
}

// Plain small text (built-in font 1 or 2)
void drawSmall(const String& s, int x, int y, uint8_t datum, uint16_t col, uint8_t font = 2) {
  tft.setTextDatum(datum);
  tft.setTextColor(col, C_BG);
  tft.drawString(s, x, y, font);
}

// Huge digits (gravity / time on the big and clock pages)
void drawHuge(const String& s, int x, int y, uint16_t col) {
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(col, C_BG);
  tft.drawString(s, x, y, 8);
}

void drawQR(const String& text, int cx, int y, int scale) {
  QRCode qr;
  uint8_t buf[qrcode_getBufferSize(3)];
  qrcode_initText(&qr, buf, 3, ECC_LOW, text.c_str());
  int size = qr.size * scale, pad = 2 * scale;
  int x = cx - size / 2;
  tft.fillRect(x - pad, y - pad, size + 2 * pad, size + 2 * pad, TFT_WHITE);
  for (uint8_t my = 0; my < qr.size; my++)
    for (uint8_t mx = 0; mx < qr.size; mx++)
      if (qrcode_getModule(&qr, mx, my)) tft.fillRect(x + mx * scale, y + my * scale, scale, scale, TFT_BLACK);
}

void drawCard(const char* title, uint16_t titleColor, const String& qr,
              const String& l1, const String& l2 = "", const String& l3 = "") {
  tft.fillScreen(C_BG);
  drawSmall(title, 120, 4, TC_DATUM, titleColor, 4);
  int y = 40;
  if (qr.length()) { drawQR(qr, 120, 42, 4); y = 42 + 29 * 4 + 12; }
  const String* ls[] = {&l1, &l2, &l3};
  for (auto l : ls) { if (l->length()) { drawSmall(*l, 120, y, TC_DATUM, C_TEXT); y += 20; } }
}

void drawMessage(const char* title, const String& l1, const String& l2 = "", const String& l3 = "") {
  tft.fillScreen(C_BG);
  drawSmall(title, 120, 30, TC_DATUM, C_SG, 4);
  const String* ls[] = {&l1, &l2, &l3};
  int y = 80;
  for (auto l : ls) { if (l->length()) { drawSmall(*l, 120, y, TC_DATUM, C_TEXT); y += 26; } }
}

void splitHint(const String& s, String& a, String& b) {
  if (s.length() <= 34) { a = s; b = ""; return; }
  int cut = s.lastIndexOf(' ', 34);
  if (cut < 10) cut = 34;
  a = s.substring(0, cut); b = s.substring(cut + 1);
  if (b.length() > 36) b = b.substring(0, 35) + ".";
}

void drawProblem() {
  String a, b; splitHint(lastHint, a, b);
  tft.fillScreen(C_BG);
  drawSmall(lastError, 120, 6, TC_DATUM, C_ERR);
  drawSmall(a, 120, 28, TC_DATUM, C_TEXT);
  if (b.length()) drawSmall(b, 120, 46, TC_DATUM, C_TEXT);
  drawQR(settingsUrl(), 120, 76, 4);
  drawSmall("or open " + easyAddress(), 120, 212, TC_DATUM, C_DIM);
}

String ageText(time_t t) {
  long mins = t ? (long)(time(nullptr) - t) / 60 : -1;
  if (mins < 0)    return "no data yet";
  if (mins < 1)    return "just now";
  if (mins < 120)  return String(mins) + "m ago";
  if (mins < 2880) return String(mins / 60) + "h ago";
  return String(mins / 1440) + "d ago";
}

int brewDay() {
  time_t start = cur.batchStart ? cur.batchStart : cur.firstT;
  return start ? (int)((time(nullptr) - start) / 86400) + 1 : 0;
}

float abvNow() {
  if (isnan(cur.og) || isnan(cur.sg)) return NAN;
  float a = (cur.og - cur.sg) * 131.25f;
  return a < 0 ? 0 : a;
}

// Status line under the big numbers on the graph page
void drawStatusLine() {
  tft.fillRect(0, 76, 240, 18, C_BG);
  if (lastError.length()) { drawSmall(lastError.substring(0, 38), 4, 78, TL_DATUM, C_ERR); return; }
  if (cfg.keg && cur.ready) {
    drawSmall("Ready to keg?", 4, 78, TL_DATUM, C_OK);
    drawSmall(ageText(cur.lastT), 236, 78, TR_DATUM, C_DIM);
    return;
  }
  String line;
  int day = brewDay();
  if (day > 0) line += "Day " + String(day) + "  ";
  if (!isnan(cur.og)) line += "OG " + fmt(cur.og, 3) + "  ";
  line += ageText(cur.lastT);
  drawSmall(line, 4, 78, TL_DATUM, C_DIM);
}

void drawHeader(const char* name, int battery) {
  String n = name[0] ? name : "FermFrame";
  if (n.length() > 22) n = n.substring(0, 22);
  drawSmall(n, 4, 2, TL_DATUM, C_TEXT);
  if (battery >= 0) drawSmall(String(battery) + "%", 236, 2, TR_DATUM, battery < 20 ? C_ERR : C_DIM);
}

void degreeAfter(int rightX, int topY, uint16_t col) { tft.drawCircle(rightX + 5, topY + 3, 2, col); }

void drawGraphPage() {
  tft.fillScreen(C_BG);
  drawHeader(cur.name, cur.battery);

  drawStyled(isnan(cur.sg) ? String("-.---") : fmt(cur.sg, 3), 4, 22, TL_DATUM, C_SG, BIG);
  drawStyled(isnan(cur.temp) ? String("--") : fmt(showTemp(cur.temp), 1), 226, 24, TR_DATUM, C_TEMP, MID);
  degreeAfter(226, 24, C_TEMP);
  float abv = abvNow();
  if (!isnan(abv)) drawSmall(fmt(abv, 1) + "% ABV", 236, 56, TR_DATUM, C_TEXT);

  drawStatusLine();

  bool wantSg = strcmp(cfg.plot, "temp") != 0, wantT = strcmp(cfg.plot, "sg") != 0;
  float sgMin = 9, sgMax = 0, tMin = 999, tMax = -999;
  for (int i = 0; i < NB; i++) {
    if (nSg[i]) { float v = sumSg[i] / nSg[i]; sgMin = min(sgMin, v); sgMax = max(sgMax, v); }
    if (nT[i])  { float v = showTemp(sumT[i] / nT[i]); tMin = min(tMin, v); tMax = max(tMax, v); }
  }
  bool haveSg = wantSg && sgMax > 0, haveT = wantT && tMax > -999;

  // grid: 4 rows, columns per day (or every 6 hours for 24 h, per week when long)
  for (int k = 0; k <= 4; k++) tft.drawFastHLine(GX0, GY0 + (GY1 - GY0) * k / 4, GX1 - GX0, C_GRID);
  long span = (long)(winEnd - winStart);
  long step = span <= 2 * 86400L ? 6 * 3600L : span <= 16 * 86400L ? 86400L : 7 * 86400L;
  for (long s = step; s < span; s += step) tft.drawFastVLine(GX1 - (int)((long long)(GX1 - GX0) * s / span), GY0, GY1 - GY0, C_GRID);

  if (!haveSg && !haveT) {
    drawSmall("No readings yet in this range", 120, (GY0 + GY1) / 2, MC_DATUM, C_DIM);
    return;
  }
  if (haveSg) { if (sgMax - sgMin < 0.004f) { float m = (sgMax + sgMin) / 2; sgMin = m - 0.002f; sgMax = m + 0.002f; }
                float p = (sgMax - sgMin) * 0.08f; sgMin -= p; sgMax += p; }
  if (haveT)  { if (tMax - tMin < 2.0f) { float m = (tMax + tMin) / 2; tMin = m - 1; tMax = m + 1; }
                float p = (tMax - tMin) * 0.08f; tMin -= p; tMax += p; }

  if (haveSg) for (int k = 0; k <= 4; k += 2) {
    float v = sgMax - (sgMax - sgMin) * k / 4;
    int pts = (int)lroundf((v - 1.0f) * 1000);
    char b[8]; snprintf(b, sizeof(b), "%03d", constrain(pts, 0, 999));   // 1.012 -> "012"
    drawSmall(b, GX0 - 3, GY0 + (GY1 - GY0) * k / 4, MR_DATUM, C_SG, 1);
  }
  if (haveT) for (int k = 0; k <= 4; k += 2)
    drawSmall(fmt(tMax - (tMax - tMin) * k / 4, 1), GX1 + 3, GY0 + (GY1 - GY0) * k / 4, ML_DATUM, C_TEMP, 1);

  auto yOf = [](float v, float lo, float hi) { return GY1 - (int)((v - lo) / (hi - lo) * (GY1 - GY0)); };
  bool area = !strcmp(cfg.graph, "area"), dots = !strcmp(cfg.graph, "dots"), bars = !strcmp(cfg.graph, "bars");

  // Gravity
  if (haveSg && bars) {
    // one bar per day (or per 2 hours for a 24-hour range)
    long barSecs = span <= 2 * 86400L ? 2 * 3600L : 86400L;
    int nBars = constrain((int)((span + barSecs - 1) / barSecs), 1, NB);
    for (int b = 0; b < nBars; b++) {
      int i0 = b * NB / nBars, i1 = (b + 1) * NB / nBars;
      float s = 0; int n = 0;
      for (int i = i0; i < i1; i++) { s += sumSg[i]; n += nSg[i]; }
      if (!n) continue;
      int x0 = GX0 + i0 * 2 + 1, w = max(2, (i1 - i0) * 2 - 2), y = yOf(s / n, sgMin, sgMax);
      tft.fillRect(x0, y, w, GY1 - y, tft.alphaBlend(170, C_SG, C_BG));
      tft.drawFastHLine(x0, y, w, C_SG);
    }
  } else if (haveSg) {
    uint16_t fill = tft.alphaBlend(80, C_SG, C_BG);
    int px = -1, py = 0;
    for (int i = 0; i < NB; i++) {
      if (!nSg[i]) continue;
      int x = GX0 + i * 2 + 1, y = yOf(sumSg[i] / nSg[i], sgMin, sgMax);
      if (dots) { tft.fillRect(x - 1, y - 1, 3, 3, C_SG); continue; }
      if (area && px >= 0)
        for (int xx = px; xx <= x; xx++) {
          int yy = py + (y - py) * (xx - px) / max(1, x - px);
          tft.drawFastVLine(xx, yy, GY1 - yy, fill);
        }
      px = x; py = y;
    }
    if (!dots) {   // the line itself, on top of the fill
      px = -1;
      for (int i = 0; i < NB; i++) {
        if (!nSg[i]) continue;
        int x = GX0 + i * 2 + 1, y = yOf(sumSg[i] / nSg[i], sgMin, sgMax);
        if (px >= 0) { tft.drawLine(px, py, x, y, C_SG); tft.drawLine(px, py + 1, x, y + 1, C_SG); }
        px = x; py = y;
      }
    }
  }
  // Temperature (always a thin line, or dots in dot mode)
  if (haveT) {
    int px = -1, py = 0;
    for (int i = 0; i < NB; i++) {
      if (!nT[i]) continue;
      int x = GX0 + i * 2 + 1, y = yOf(showTemp(sumT[i] / nT[i]), tMin, tMax);
      if (dots) { tft.fillRect(x - 1, y - 1, 2, 2, C_TEMP); continue; }
      if (px >= 0) tft.drawLine(px, py, x, y, C_TEMP);
      px = x; py = y;
    }
  }

  String left = cfg.rangeH == 0 ? String("start") : cfg.rangeH < 48 ? String("-24h") : "-" + String(cfg.rangeH / 24) + "d";
  drawSmall(left, GX0, 240, BL_DATUM, C_DIM, 1);
  drawSmall("now", GX1, 240, BR_DATUM, C_DIM, 1);
  if (haveSg) drawSmall("SG", 70, 240, BC_DATUM, C_SG, 1);
  if (haveT)  drawSmall(isFahrenheit() ? "Temp F" : "Temp C", 160, 240, BC_DATUM, C_TEMP, 1);
}

void drawBigPage() {
  tft.fillScreen(C_BG);
  drawHeader(cur.name, cur.battery);
  drawSmall("GRAVITY", 120, 30, TC_DATUM, C_DIM);
  drawHuge(isnan(cur.sg) ? String("-.---") : fmt(cur.sg, 3), 120, 88, C_SG);
  drawStyled(isnan(cur.temp) ? String("--") : fmt(showTemp(cur.temp), 1), 64, 150, MC_DATUM, C_TEMP, MID);
  float abv = abvNow();
  drawStyled(isnan(abv) ? String("--") : fmt(abv, 1), 176, 150, MC_DATUM, C_TEXT, MID);
  drawSmall(isFahrenheit() ? "temp F" : "temp C", 64, 174, TC_DATUM, C_DIM);
  drawSmall("% ABV", 176, 174, TC_DATUM, C_DIM);
  if (lastError.length())      drawSmall(lastError.substring(0, 38), 120, 200, TC_DATUM, C_ERR);
  else if (cfg.keg && cur.ready) drawSmall("Ready to keg?", 120, 200, TC_DATUM, C_OK, 4);
  else if (brewDay() > 0)      drawSmall("Day " + String(brewDay()), 120, 200, TC_DATUM, C_TEXT, 4);
  drawSmall(ageText(cur.lastT), 120, 226, TC_DATUM, C_DIM);
}

void drawClockPage() {
  tft.fillScreen(C_BG);
  time_t now = time(nullptr);
  struct tm lt; localtime_r(&now, &lt);
  char hm[6]; snprintf(hm, sizeof(hm), "%02d:%02d", lt.tm_hour % 100, lt.tm_min % 100);
  static const char* DAYS[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  static const char* MONTHS[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  drawHuge(hm, 120, 84, C_TEXT);
  drawStyled(String(DAYS[lt.tm_wday % 7]) + " " + lt.tm_mday + " " + MONTHS[lt.tm_mon % 12], 120, 150, MC_DATUM, C_DIM, MID);
  tft.drawFastHLine(20, 186, 200, C_GRID);
  String line = String(cur.name[0] ? cur.name : "FermFrame");
  if (line.length() > 16) line = line.substring(0, 16);
  drawSmall(line, 120, 196, TC_DATUM, C_TEXT);
  String vals = (isnan(cur.sg) ? String("-.---") : fmt(cur.sg, 3)) + "    " +
                (isnan(cur.temp) ? String("--") : fmt(showTemp(cur.temp), 1)) + (isFahrenheit() ? " F" : " C");
  drawSmall(vals, 120, 216, TC_DATUM, C_SG);
}

void drawBrewPage(int k) {
  Brew& b = others[k];
  tft.fillScreen(C_BG);
  drawHeader(b.name, -1);
  drawSmall("GRAVITY", 120, 30, TC_DATUM, C_DIM);
  drawHuge(isnan(b.sg) ? String("-.---") : fmt(b.sg, 3), 120, 88, C_SG);
  drawStyled(isnan(b.temp) ? String("--") : fmt(showTemp(b.temp), 1), 120, 150, MC_DATUM, C_TEMP, MID);
  drawSmall(isFahrenheit() ? "temp F" : "temp C", 120, 174, TC_DATUM, C_DIM);
  drawSmall(ageText(b.lastT), 120, 204, TC_DATUM, C_DIM);
  drawSmall("Brew " + String(k + 2) + " of " + String(nOthers + 1), 120, 224, TC_DATUM, C_DIM);
}

// Page list: 0 = graph, 1 = big, 2 = clock, 10+k = other brew k
int pages[3 + 4]; int nPages = 0;
void buildPages() {
  nPages = 0;
  if (cfg.pgGraph) pages[nPages++] = 0;
  if (cfg.pgBig)   pages[nPages++] = 1;
  if (cfg.pgClock) pages[nPages++] = 2;
  if (cfg.pgBrews) for (int k = 0; k < nOthers; k++) pages[nPages++] = 10 + k;
  if (!nPages) pages[nPages++] = 0;
  if (pageIndex >= nPages) pageIndex = 0;
}

int currentPage() { return pages[pageIndex < nPages ? pageIndex : 0]; }

void drawCurrentPage() {
  buildPages();
  int p = currentPage();
  if (p == 0) drawGraphPage();
  else if (p == 1) drawBigPage();
  else if (p == 2) drawClockPage();
  else if (p >= 10 && p - 10 < nOthers) drawBrewPage(p - 10);
  else drawGraphPage();
}

void showSetupScreen() {
  if (cfg.ssid[0])
    drawCard("Can't find Wi-Fi", C_ERR, "WIFI:T:nopass;S:" + String(AP_NAME) + ";;",
             String("Looking for: ") + cfg.ssid, "Retrying soon. To change it,", "scan with your phone camera");
  else
    drawCard("Set me up!", C_SG, "WIFI:T:nopass;S:" + String(AP_NAME) + ";;",
             "Scan with your phone camera", String("or join Wi-Fi ") + AP_NAME, "then fill in the page");
}

void showAccountNeeded() {
  drawCard("Almost there!", C_SG, settingsUrl(),
           "Scan to add your Brewfather", "or RAPT details", "or open " + easyAddress());
}

// ------------------------------------------------------------------ web pages

// Sends a page in pieces so a big page doesn't need lots of memory
struct Chunker {
  String b;
  Chunker() { b.reserve(1400); }
  void add(const String& s) { b += s; if (b.length() > 1200) flush(); }
  void add(const char* s) { b += s; if (b.length() > 1200) flush(); }
  void add(const __FlashStringHelper* s) { b += s; if (b.length() > 1200) flush(); }
  void addP(PGM_P p) { flush(); server.sendContent_P(p); }
  void flush() { if (b.length()) { server.sendContent(b); b = ""; } }
};

static const char PAGE_HEAD[] PROGMEM =
  "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
  "<title>FermFrame</title><style>"
  "body{font:18px system-ui,sans-serif;margin:0;background:#111;color:#eee}"
  "main{max-width:460px;margin:auto;padding:16px}h1{color:#fc3;margin:.2em 0}"
  "h2{font-size:1.05em;margin:1.6em 0 .4em;color:#fc3}label{display:block;margin:.7em 0 .2em}"
  "input,select{box-sizing:border-box;width:100%;font-size:18px;padding:10px;border-radius:8px;border:1px solid #555;background:#222;color:#eee}"
  "input[type=color]{padding:2px;height:44px}input[type=checkbox]{width:auto;transform:scale(1.4);margin-right:10px}"
  ".r{display:flex;gap:10px}.r>label{flex:1;margin:0;padding:12px;border:1px solid #555;border-radius:8px;text-align:center}"
  ".r input{width:auto}.c{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}.c label{margin:.3em 0 .1em;font-size:15px}"
  ".k label{display:flex;align-items:center;margin:.6em 0}small{color:#aaa;display:block;margin-top:.3em}"
  "button{width:100%;margin-top:1.4em;padding:14px;font-size:20px;border:0;border-radius:10px;background:#fc3;color:#111;font-weight:700}"
  "button.s{background:#333;color:#eee;font-size:16px;padding:10px;margin-top:.6em}"
  "#pv{position:sticky;top:0;background:#111;padding:8px 0;text-align:center;z-index:2}"
  "canvas{width:240px;height:240px;border-radius:14px;border:6px solid #333;background:#000}"
  ".t{display:flex;gap:6px;justify-content:center;margin-top:6px}.t button{width:auto;margin:0;padding:6px 12px;font-size:14px;background:#333;color:#eee}"
  "details{margin-top:1.4em}a{color:#fc3}</style></head><body><main><h1>&#127866; FermFrame</h1>";

static const char PAGE_JS[] PROGMEM = R"JS(<script>
var D=null,pv='graph',K=['cBg','cText','cSg','cTemp','cGrid','cDim'];
var T={beer:['#000000','#ffffff','#ffcc00','#5bbeff','#2a2a2a','#8c8c8c'],dark:['#101418','#e6e6e6','#7cd992','#f2a65a','#2b3138','#8a949e'],
light:['#ffffff','#111111','#c77700','#1f6fd1','#dddddd','#777777'],retro:['#000000','#33ff66','#33ff66','#b8ff66','#0f3318','#1f9944'],
contrast:['#000000','#ffffff','#ffff00','#00ffff','#555555','#cccccc']};
function g(i){return document.getElementById(i)}
function v(n){var e=document.querySelector('[name='+n+']:checked')||document.getElementsByName(n)[0];return e?e.value:''}
function s(){var b=g('bf').checked;g('l1').textContent=b?'Brewfather user ID':'RAPT email';g('l2').textContent=b?'Brewfather API key':'RAPT API secret';
g('h').innerHTML=b?'In Brewfather: Settings &rarr; API &rarr; Generate API key (tick <b>Read batches</b>). Copy the User ID and the key. One key works for all your clocks.':'On the RAPT website: My account &rarr; API Secrets &rarr; Create. Use your RAPT email, not your password.'}
function th(){var t=T[g('theme').value];if(t)K.forEach(function(k,i){g(k).value=t[i]});P()}
function cu(){g('theme').value='custom';P()}
function rs(){g('theme').value='beer';th();g('font').value='digital';g('bright').value=100;g('night').checked=false;g('graph').value='line';g('plot').value='both';g('range').value='168';
['pgBig','pgClock','pgBrews'].forEach(function(k){g(k).checked=false});g('pgGraph').checked=true;g('pageSecs').value=10;g('keg').checked=true;P()}
function sample(){var a=[],b=[];for(var i=0;i<88;i++){a.push(1.012+0.038/(1+Math.exp((i-30)/8)));b.push(18+1.2*Math.sin(i/9))}return{name:'Example Pale Ale',sg:a[87],temp:b[87],og:1.050,bat:57,s:a,t:b,day:6,ready:0}}
function fnt(sz){var f=g('font').value;return 'bold '+sz+'px '+(f=='modern'?'Arial,Helvetica,sans-serif':f=='classic'?'Georgia,Times,serif':'"Courier New",monospace')}
function P(){var c=g('cv'),x=c.getContext('2d'),C={};K.forEach(function(k){C[k]=g(k).value});
var d=(D&&D.s&&D.s.some(function(q){return q!=null}))?D:sample(),F=v('unit')=='F',tc=function(t){return F?t*9/5+32:t};
c.style.opacity=Math.max(.25,g('bright').value/100);
x.fillStyle=C.cBg;x.fillRect(0,0,240,240);x.textBaseline='top';
function tx(s,X,Y,col,f,al){x.fillStyle=col;x.font=f;x.textAlign=al||'left';x.fillText(s,X,Y)}
var sg=d.sg?d.sg.toFixed(3):'-.---',tp=d.temp!=null?tc(d.temp).toFixed(1):'--',abv=d.og&&d.sg?((d.og-d.sg)*131.25).toFixed(1):'--';
if(pv=='clock'){var n=new Date();tx(('0'+n.getHours()).slice(-2)+':'+('0'+n.getMinutes()).slice(-2),120,50,C.cText,'bold 66px Arial','center');
tx(n.toDateString().slice(0,10),120,135,C.cDim,fnt(24),'center');x.fillStyle=C.cGrid;x.fillRect(20,186,200,1);
tx(d.name.slice(0,16),120,196,C.cText,'14px Arial','center');tx(sg+'    '+tp+(F?' F':' C'),120,216,C.cSg,'14px Arial','center');return}
tx(d.name.slice(0,22),4,3,C.cText,'14px Arial');if(d.bat!=null)tx(d.bat+'%',236,3,C.cDim,'14px Arial','right');
if(pv=='big'){tx('GRAVITY',120,30,C.cDim,'14px Arial','center');tx(sg,120,52,C.cSg,'bold 66px Arial','center');
tx(tp,64,138,C.cTemp,fnt(26),'center');tx(abv,176,138,C.cText,fnt(26),'center');tx(F?'temp F':'temp C',64,174,C.cDim,'14px Arial','center');tx('% ABV',176,174,C.cDim,'14px Arial','center');
tx(g('keg').checked&&d.ready?'Ready to keg?':'Day '+(d.day||1),120,200,g('keg').checked&&d.ready?'#5f5':C.cText,'bold 20px Arial','center');return}
var dig=g('font').value=='digital';tx(sg,4,22,C.cSg,dig?'bold 44px "Courier New",monospace':fnt(40));tx(tp+'°',236,24,C.cTemp,dig?'bold 26px Arial':fnt(26),'right');tx(abv+'% ABV',236,56,C.cText,'14px Arial','right');
tx(g('keg').checked&&d.ready?'Ready to keg?':'Day '+(d.day||1)+'  OG '+(d.og?d.og.toFixed(3):'-')+'  5m ago',4,78,g('keg').checked&&d.ready?'#5f5':C.cDim,'14px Arial');
var X0=32,X1=208,Y0=98,Y1=222;x.fillStyle=C.cGrid;for(var k=0;k<=4;k++)x.fillRect(X0,Y0+(Y1-Y0)*k/4,X1-X0,1);
var pl=g('plot').value,ty=g('graph').value,S=d.s.slice(),Tt=d.t.map(function(q){return q==null?null:tc(q)});
function rg(a){var f=a.filter(function(q){return q!=null});if(!f.length)return null;var lo=Math.min.apply(0,f),hi=Math.max.apply(0,f);var p=(hi-lo)*.08||.002;return[lo-p,hi+p]}
var rs_=pl!='temp'?rg(S):null,rt=pl!='sg'?rg(Tt):null;function Y(q,r){return Y1-(q-r[0])/(r[1]-r[0])*(Y1-Y0)}
if(rs_){x.strokeStyle=C.cSg;x.fillStyle=C.cSg;x.lineWidth=2;
if(ty=='bars'){var nb=Math.max(1,Math.round(+g('range').value/24)||7);for(var b=0;b<nb;b++){var i0=Math.floor(b*88/nb),i1=Math.floor((b+1)*88/nb),f=S.slice(i0,i1).filter(function(q){return q!=null});if(!f.length)continue;
var m=f.reduce(function(a,q){return a+q},0)/f.length,yy=Y(m,rs_);x.globalAlpha=.65;x.fillRect(X0+i0*2+1,yy,(i1-i0)*2-2,Y1-yy);x.globalAlpha=1}}
else{if(ty=='area'){x.globalAlpha=.32;x.beginPath();var st=0;S.forEach(function(q,i){if(q==null)return;var X=X0+i*2+1;if(!st){x.moveTo(X,Y1);st=X}x.lineTo(X,Y(q,rs_));x.lastX=X});x.lineTo(x.lastX,Y1);x.fill();x.globalAlpha=1}
x.beginPath();var on=0;S.forEach(function(q,i){if(q==null)return;var X=X0+i*2+1,yy=Y(q,rs_);if(ty=='dots'){x.fillRect(X-1,yy-1,3,3);return}on?x.lineTo(X,yy):x.moveTo(X,yy);on=1});if(ty!='dots')x.stroke()}}
if(rt){x.strokeStyle=C.cTemp;x.fillStyle=C.cTemp;x.lineWidth=1;x.beginPath();var on2=0;Tt.forEach(function(q,i){if(q==null)return;var X=X0+i*2+1,yy=Y(q,rt);if(ty=='dots'){x.fillRect(X-1,yy-1,2,2);return}on2?x.lineTo(X,yy):x.moveTo(X,yy);on2=1});if(ty!='dots')x.stroke()}
var r=g('range').value;tx(r=='0'?'start':r=='24'?'-24h':'-'+(r/24)+'d',X0,231,C.cDim,'10px Arial');tx('now',X1,231,C.cDim,'10px Arial','right')}
function pg(n){pv=n;P()}
document.querySelectorAll('input,select').forEach(function(e){e.addEventListener('input',P);e.addEventListener('change',P)});
K.forEach(function(k){g(k).addEventListener('input',cu)});g('theme').addEventListener('change',th);
s();P();fetch('/graph.json').then(function(r){return r.json()}).then(function(j){D=j;P()}).catch(function(){});
</script>)JS";

void addOption(Chunker& c, const char* value, const __FlashStringHelper* label, const char* current) {
  c.add(F("<option value=\"")); c.add(value); c.add("\"");
  if (!strcmp(value, current)) c.add(" selected");
  c.add(">"); c.add(label); c.add("</option>");
}

void addColour(Chunker& c, const char* name, const __FlashStringHelper* label, const char* value) {
  c.add("<div><label>"); c.add(label); c.add(F("</label><input type=color id=")); c.add(name);
  c.add(" name="); c.add(name); c.add(" value=\""); c.add(value); c.add("\"></div>");
}

void addCheck(Chunker& c, const char* name, const __FlashStringHelper* label, bool on) {
  c.add(F("<label><input type=checkbox id=")); c.add(name); c.add(" name="); c.add(name); c.add(" value=1");
  if (on) c.add(" checked");
  c.add(">"); c.add(label); c.add("</label>");
}

void handleRoot() {
  lastWebHit = millis();
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "text/html", "");
  Chunker c;
  c.addP(PAGE_HEAD);

#ifndef FERMFRAME_LITE
  c.add(F("<div id=pv><canvas id=cv width=240 height=240></canvas><div class=t>"
        "<button type=button onclick=\"pg('graph')\">Graph</button><button type=button onclick=\"pg('big')\">Big number</button>"
        "<button type=button onclick=\"pg('clock')\">Clock</button></div></div>"));
#endif
  c.add(F("<form method=post action=/save><input type=hidden name=look value=1>"));

  c.add(F("<h2>1. Your home Wi-Fi</h2><label>Wi-Fi name</label><input name=ssid list=nets autocomplete=off required value=\""));
  c.add(html(cfg.ssid)); c.add(F("\"><datalist id=nets>")); c.add(netOptions);
  c.add(F("</datalist><label>Wi-Fi password</label><input name=pass type=password placeholder=\""));
  c.add(cfg.pass[0] ? "(saved - leave blank to keep)" : "password"); c.add("\">");

  bool bf = isBrewfather();
  c.add(F("<h2>2. Where are your readings?</h2><div class=r><label><input type=radio name=source value=brewfather id=bf onchange=s()"));
  if (bf) c.add(" checked");
  c.add(F("> Brewfather</label><label><input type=radio name=source value=rapt onchange=s()"));
  if (!bf) c.add(" checked");
  c.add(F("> RAPT Pill</label></div><small id=h></small><label id=l1>ID</label><input name=user autocomplete=off autocapitalize=off value=\""));
  c.add(html(cfg.user));
  c.add(F("\"><label id=l2>Key</label><input name=secret type=password autocomplete=off placeholder=\""));
  c.add(cfg.secret[0] ? "(saved - leave blank to keep)" : "paste it here"); c.add("\">");

#ifndef FERMFRAME_LITE
  // ---- Look & feel ----
  c.add(F("<h2>3. Look &amp; feel</h2><label>Theme</label><select id=theme name=theme>"));
  addOption(c, "beer", F("Beer (amber on black)"), cfg.theme); addOption(c, "dark", F("Dark"), cfg.theme);
  addOption(c, "light", F("Light"), cfg.theme); addOption(c, "retro", F("Retro green"), cfg.theme);
  addOption(c, "contrast", F("High contrast"), cfg.theme); addOption(c, "custom", F("My own colours"), cfg.theme);
  c.add(F("</select><div class=c>"));
  addColour(c, "cBg", F("Background"), cfg.cBg); addColour(c, "cText", F("Text"), cfg.cText);
  addColour(c, "cSg", F("Gravity"), cfg.cSg); addColour(c, "cTemp", F("Temperature"), cfg.cTemp);
  addColour(c, "cGrid", F("Grid"), cfg.cGrid); addColour(c, "cDim", F("Small text"), cfg.cDim);
  c.add(F("</div><label>Font</label><select id=font name=font>"));
  addOption(c, "digital", F("Digital (7-segment)"), cfg.font); addOption(c, "modern", F("Modern"), cfg.font);
  addOption(c, "classic", F("Classic"), cfg.font); addOption(c, "mono", F("Typewriter"), cfg.font);
  c.add(F("</select><label>Brightness</label><input type=range id=bright name=bright min=5 max=100 value="));
  c.add(String(cfg.bright));
  c.add(F("><div class=k>")); addCheck(c, "night", F("Dim at night"), cfg.night); c.add(F("</div><div class=c><div><label>From</label><input type=time name=nFrom value="));
  c.add(cfg.nFrom); c.add(F("></div><div><label>To</label><input type=time name=nTo value=")); c.add(cfg.nTo);
  c.add(F("></div><div><label>Night level</label><select name=nLevel>"));
  { const int lv[] = {0, 5, 15, 30}; const __FlashStringHelper* nm[] = {F("Off"), F("Very dim"), F("Dim"), F("Medium")};
    for (int i = 0; i < 4; i++) { c.add(F("<option value=")); c.add(String(lv[i])); if (cfg.nLevel == lv[i]) c.add(" selected"); c.add(">"); c.add(nm[i]); c.add("</option>"); } }
  c.add(F("</select></div></div>"));

  // ---- Graph ----
  c.add(F("<h2>4. Graph</h2><label>Graph style</label><select id=graph name=graph>"));
  addOption(c, "line", F("Line"), cfg.graph); addOption(c, "area", F("Filled area"), cfg.graph);
  addOption(c, "dots", F("Dots"), cfg.graph); addOption(c, "bars", F("Daily bars"), cfg.graph);
  c.add(F("</select><label>Show</label><select id=plot name=plot>"));
  addOption(c, "both", F("Gravity and temperature"), cfg.plot); addOption(c, "sg", F("Gravity only"), cfg.plot);
  addOption(c, "temp", F("Temperature only"), cfg.plot);
  c.add(F("</select><label>Time range</label><select id=range name=range>"));
  { const int rv[] = {24, 72, 168, 336, 0}; const __FlashStringHelper* rn[] = {F("Last 24 hours"), F("Last 3 days"), F("Last 7 days"), F("Last 14 days"), F("Whole batch")};
    for (int i = 0; i < 5; i++) { c.add(F("<option value=")); c.add(String(rv[i])); if (cfg.rangeH == rv[i]) c.add(" selected"); c.add(">"); c.add(rn[i]); c.add("</option>"); } }
  c.add("</select>");

  // ---- Pages ----
  c.add(F("<h2>5. Pages</h2><small>Tick more than one and the clock takes turns showing them.</small><div class=k>"));
  addCheck(c, "pgGraph", F("Graph"), cfg.pgGraph); addCheck(c, "pgBig", F("Big number"), cfg.pgBig);
  addCheck(c, "pgClock", F("Clock (time and date)"), cfg.pgClock); addCheck(c, "pgBrews", F("My other fermenting brews"), cfg.pgBrews);
  c.add(F("</div><label>Seconds per page</label><input type=number id=pageSecs name=pageSecs min=3 max=600 value="));
  c.add(String(cfg.pageSecs));
  c.add(F("><div class=k>")); addCheck(c, "keg", F("Show \"Ready to keg?\" when gravity hasn't moved for 3 days"), cfg.keg);
  c.add(F("</div><button type=button class=s onclick=rs()>Reset the look to normal</button>"));
#endif

  // ---- More ----
  c.add(F("<details><summary>More options</summary><label>Batch or pill name (blank = the first one)</label><input name=device value=\""));
  c.add(html(cfg.device));
  c.add(F("\"><label>Temperature</label><div class=r><label><input type=radio name=unit value=C"));
  if (!isFahrenheit()) c.add(" checked");
  c.add(F("> &deg;C</label><label><input type=radio name=unit value=F"));
  if (isFahrenheit()) c.add(" checked");
  c.add(F("> &deg;F</label></div><label>Time zone</label><select name=tz id=tz>"));
  {
    static const char* const TZ[][2] = {
      {"GMT0BST,M3.5.0/1,M10.5.0", "UK and Ireland"}, {"CET-1CEST,M3.5.0,M10.5.0/3", "Central Europe"},
      {"EET-2EEST,M3.5.0/3,M10.5.0/4", "Eastern Europe"}, {"EST5EDT,M3.2.0,M11.1.0", "US Eastern"},
      {"CST6CDT,M3.2.0,M11.1.0", "US Central"}, {"MST7MDT,M3.2.0,M11.1.0", "US Mountain"},
      {"PST8PDT,M3.2.0,M11.1.0", "US Pacific"}, {"AEST-10AEDT,M10.1.0,M4.1.0/3", "Australia (Sydney, Melbourne)"},
      {"AEST-10", "Australia (Brisbane)"}, {"NZST-12NZDT,M9.5.0,M4.1.0/3", "New Zealand"},
      {"SAST-2", "South Africa"}, {"UTC0", "UTC"}};
    for (auto& z : TZ) addOption(c, z[0], FPSTR(z[1]), cfg.tz);
  }
  c.add(F("</select><label>Check for new readings every (minutes)</label><input name=refresh type=number min=1 max=120 value="));
  c.add(String(cfg.refreshMin));
  c.add(F("><p><a href=/update>Install new firmware</a></p></details><button>Save</button></form>"));
#ifndef FERMFRAME_LITE
  c.addP(PAGE_JS);
#else
  c.add(F("<script>function s(){}</script>"));
#endif
  c.add("<small>FermFrame " FERMFRAME_VERSION " (" FERMFRAME_BUILD ")</small></main></body></html>");
  c.flush();
  server.sendContent("");
}

void copyArg(const char* name, char* dst, size_t n, bool keepIfBlank) {
  if (!server.hasArg(name)) return;
  String v = server.arg(name); v.trim();
  if (keepIfBlank && !v.length()) return;
  strlcpy(dst, v.c_str(), n);
}

int argInt(const char* name, int def, int lo, int hi) {
  if (!server.hasArg(name)) return def;
  String v = server.arg(name); v.trim();
  if (!v.length()) return def;
  return constrain(v.toInt(), lo, hi);
}

void copyColour(const char* name, char* dst) {
  if (!server.hasArg(name)) return;
  String v = server.arg(name);
  if (v.length() == 7 && v[0] == '#') strlcpy(dst, v.c_str(), 8);
}

void handleSave() {
  lastWebHit = millis();
  String oldSsid = cfg.ssid, oldPass = cfg.pass;

  copyArg("ssid", cfg.ssid, sizeof(cfg.ssid), false);
  if (server.hasArg("pass") && server.arg("pass").length()) strlcpy(cfg.pass, server.arg("pass").c_str(), sizeof(cfg.pass));
  String src = server.arg("source"); src.toLowerCase();
  if (src.length()) strlcpy(cfg.source, src.startsWith("r") ? "rapt" : "brewfather", sizeof(cfg.source));
  copyArg("user", cfg.user, sizeof(cfg.user), false);
  copyArg("secret", cfg.secret, sizeof(cfg.secret), true);
  copyArg("device", cfg.device, sizeof(cfg.device), false);
  cfg.refreshMin = argInt("refresh", cfg.refreshMin, 1, 120);
  if (server.hasArg("unit")) strlcpy(cfg.unit, (server.arg("unit")[0] == 'F' || server.arg("unit")[0] == 'f') ? "F" : "C", sizeof(cfg.unit));
  copyArg("tz", cfg.tz, sizeof(cfg.tz), true);
  if (server.hasArg("days")) cfg.rangeH = argInt("days", 7, 1, 60) * 24;   // older installers

  // Look & feel: only from the full settings form (checkboxes are missing when unticked)
  if (server.hasArg("look") && server.hasArg("theme")) {
    copyArg("theme", cfg.theme, sizeof(cfg.theme), true);
    copyColour("cBg", cfg.cBg); copyColour("cText", cfg.cText); copyColour("cSg", cfg.cSg);
    copyColour("cTemp", cfg.cTemp); copyColour("cGrid", cfg.cGrid); copyColour("cDim", cfg.cDim);
    copyArg("font", cfg.font, sizeof(cfg.font), true);
    cfg.bright = argInt("bright", cfg.bright, 5, 100);
    cfg.night = server.hasArg("night");
    copyArg("nFrom", cfg.nFrom, sizeof(cfg.nFrom), true);
    copyArg("nTo", cfg.nTo, sizeof(cfg.nTo), true);
    cfg.nLevel = argInt("nLevel", cfg.nLevel, 0, 100);
    copyArg("graph", cfg.graph, sizeof(cfg.graph), true);
    copyArg("plot", cfg.plot, sizeof(cfg.plot), true);
    if (server.hasArg("range")) { int r = server.arg("range").toInt(); cfg.rangeH = r <= 0 ? 0 : constrain(r, 24, 60 * 24); }
    cfg.pgGraph = server.hasArg("pgGraph"); cfg.pgBig = server.hasArg("pgBig");
    cfg.pgClock = server.hasArg("pgClock"); cfg.pgBrews = server.hasArg("pgBrews");
    cfg.pageSecs = argInt("pageSecs", cfg.pageSecs, 3, 600);
    cfg.keg = server.hasArg("keg");
  }
  saveConfig();

  bool wifiChanged = setupMode || oldSsid != cfg.ssid || oldPass != cfg.pass;
  String p; p += FPSTR(PAGE_HEAD);
  if (wifiChanged) {
    p += F("<h2>Saved! &#10004;</h2><p>The clock restarts and joins <b>");
    p += html(cfg.ssid);
    p += F("</b>. Your graph appears on the clock in about a minute.</p>"
#ifdef EASY_ADDRESS
           "<p>To change anything later, open <a href=http://fermframe.local/>fermframe.local</a> or scan the code on the clock.</p>"
#else
           "<p>To change anything later, scan the code on the clock.</p>"
#endif
           "</main></body></html>");
  } else {
    p += F("<h2>Saved! &#10004;</h2><p>Have a look at your clock.</p><p><a href=/>Back to the settings</a></p></main></body></html>");
  }
  server.send(200, "text/html", p);

  if (wifiChanged) {
    drawMessage("Saved!", "Restarting...");
    restartAt = millis() + 1500;
  } else {
    applyColours();
    configTime(cfg.tz, "pool.ntp.org", "time.google.com");
    currentLevel = -1; updateBacklight();
    raptToken = "";
    pageIndex = 0; pageSince = millis();
    needRefresh = true;     // fetch again (range or batch may have changed) and redraw
  }
}

// Status for the installer and for checking up on the clock
void handleInfo() {
  lastWebHit = millis();
  String j = F("{\"firmware\":\"fermframe\",\"build\":\"" FERMFRAME_BUILD "\",\"version\":\"" FERMFRAME_VERSION "\",\"mac\":\"");
  j += WiFi.macAddress();
  j += F("\",\"mode\":\"");
  j += setupMode ? F("setup") : F("wifi");
  j += F("\",\"ip\":\"");
  j += setupMode ? WiFi.softAPIP().toString() : WiFi.localIP().toString();
  j += F("\",\"wifi\":\"");
  j += html(cfg.ssid);
  j += F("\",\"account\":");
  j += hasAccount() ? F("true") : F("false");
  j += F(",\"problem\":\"");
  j += html(lastError.c_str());
  j += F("\",\"heap\":");
  j += ESP.getFreeHeap();
  j += F(",\"readings\":");
  j += cur.count;
  j += F(",\"brews\":");
  j += nOthers + (cur.name[0] ? 1 : 0);
  j += F("}");
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", j);
}

// The data behind the graph, for the live preview on the settings page
void handleGraph() {
  lastWebHit = millis();
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "application/json", "");
  Chunker c;
  c.add("{\"name\":\""); c.add(html(cur.name)); c.add("\"");
  if (!isnan(cur.sg)) { c.add(",\"sg\":"); c.add(fmt(cur.sg, 4)); }
  if (!isnan(cur.temp)) { c.add(",\"temp\":"); c.add(fmt(cur.temp, 2)); }
  if (!isnan(cur.og)) { c.add(",\"og\":"); c.add(fmt(cur.og, 4)); }
  if (cur.battery >= 0) { c.add(",\"bat\":"); c.add(String(cur.battery)); }
  c.add(",\"day\":"); c.add(String(brewDay()));
  c.add(",\"ready\":"); c.add(cur.ready ? "1" : "0");
  c.add(",\"s\":[");
  for (int i = 0; i < NB; i++) { if (i) c.add(","); c.add(nSg[i] ? fmt(sumSg[i] / nSg[i], 4) : String("null")); }
  c.add("],\"t\":[");
  for (int i = 0; i < NB; i++) { if (i) c.add(","); c.add(nT[i] ? fmt(sumT[i] / nT[i], 2) : String("null")); }
  c.add("]}");
  c.flush();
  server.sendContent("");
}

void handleNotFound() {
  lastWebHit = millis();
  if (setupMode) {   // captive portal: send every phone check to the setup page
    server.sendHeader("Location", "http://192.168.4.1/");
    server.send(302, "text/plain", "");
  } else {
    server.send(404, "text/plain", "Not found");
  }
}

void startWebServer() {
  server.on("/", HTTP_GET, handleRoot);
  server.on("/save", HTTP_POST, handleSave);
  server.on("/fermframe.json", HTTP_GET, handleInfo);
  server.on("/brewscreen.json", HTTP_GET, handleInfo);   // name before FermFrame
  server.on("/graph.json", HTTP_GET, handleGraph);
  server.onNotFound(handleNotFound);
  updater.setup(&server, "/update");
  server.begin();
}

// ------------------------------------------------------------------ Wi-Fi

void scanNetworks() {
  netOptions = "";
  int n = WiFi.scanNetworks();
  int added = 0;
  for (int i = 0; i < n && added < 15; i++) {
    String s = WiFi.SSID(i);
    if (!s.length() || s == AP_NAME || netOptions.indexOf("\"" + html(s.c_str()) + "\"") >= 0) continue;
    netOptions += "<option value=\"" + html(s.c_str()) + "\">";
    added++;
  }
  WiFi.scanDelete();
}

void startSetupMode() {
  setupMode = true;
  WiFi.disconnect();
  WiFi.mode(WIFI_STA);
  scanNetworks();
  WiFi.mode(WIFI_AP);
  IPAddress ip(192, 168, 4, 1);
  WiFi.softAPConfig(ip, ip, IPAddress(255, 255, 255, 0));
  WiFi.softAP(AP_NAME);
  dns.start(53, "*", ip);
  startWebServer();
  setupSince = lastWebHit = millis();
  showSetupScreen();
}

bool connectWifi() {
  drawMessage("FermFrame", "Connecting to Wi-Fi", cfg.ssid);
  WiFi.mode(WIFI_STA);
  WiFi.hostname(HOSTNAME);
  WiFi.setAutoReconnect(true);
  WiFi.begin(cfg.ssid, cfg.pass);
  for (int i = 0; i < 60; i++) {         // 30 seconds
    if (WiFi.status() == WL_CONNECTED) return true;
    delay(500);
  }
  return false;
}

// ------------------------------------------------------------------ main

void setup() {
  Serial.begin(115200);
  pinMode(BACKLIGHT_PIN, OUTPUT);
  analogWriteFreq(2000);
  setBacklight(100);

  if (!LittleFS.begin()) { LittleFS.format(); LittleFS.begin(); }
  loadConfig();
  applyColours();

  tft.init();
  tft.setRotation(0);
  drawMessage("FermFrame", "Starting...");

  // Wi-Fi saved by an earlier version / other firmware: take it over
  if (!cfg.ssid[0] && WiFi.SSID().length()) {
    strlcpy(cfg.ssid, WiFi.SSID().c_str(), sizeof(cfg.ssid));
    strlcpy(cfg.pass, WiFi.psk().c_str(), sizeof(cfg.pass));
    saveConfig();
  }
  WiFi.persistent(false);

  if (!cfg.ssid[0] || !connectWifi()) { startSetupMode(); return; }

  startWebServer();
#ifdef EASY_ADDRESS
  MDNS.begin(HOSTNAME);
  MDNS.addService("http", "tcp", 80);
#endif

  drawMessage("FermFrame", "Getting the time...", easyAddress(), WiFi.localIP().toString());
  configTime(cfg.tz, "pool.ntp.org", "time.google.com");
  for (int i = 0; i < 40 && time(nullptr) < 1700000000; i++) delay(250);
  updateBacklight();
}

void loop() {
  server.handleClient();
  unsigned long now = millis();
  if (restartAt && (long)(now - restartAt) > 0) ESP.restart();

  if (setupMode) {
    dns.processNextRequest();
    if (cfg.ssid[0] && WiFi.softAPgetStationNum() == 0 &&
        now - setupSince > SETUP_RETRY_MS && now - lastWebHit > SETUP_RETRY_MS) {
      ESP.restart();   // power-cut recovery: try the home Wi-Fi again
    }
    return;
  }

#ifdef EASY_ADDRESS
  MDNS.update();
#endif

#ifdef FERMFRAME_LITE
  if (needRefresh) {
    needRefresh = false;
    drawCard("Almost done", C_SG, settingsUrl(), "Run the FermFrame installer", "again to finish, or scan", "to open the settings");
  }
  return;
#else
  if (!hasAccount()) {
    if (needRefresh) { showAccountNeeded(); needRefresh = false; }
    return;
  }

  if (needRefresh || now - lastFetch > (unsigned long)cfg.refreshMin * 60000UL) {
    bool firstTime = cur.lastT == 0;
    needRefresh = false;
    lastFetch = now;
    if (time(nullptr) < 1700000000) {
      configTime(cfg.tz, "pool.ntp.org", "time.google.com");
      setProblem("Waiting for internet time", "Check your router. Trying again in a minute.");
      if (firstTime) drawProblem(); else drawCurrentPage();
      lastFetch = now - (unsigned long)cfg.refreshMin * 60000UL + 60000UL;
      return;
    }
    if (firstTime) drawMessage("FermFrame", isBrewfather() ? "Asking Brewfather..." : "Asking RAPT...");

    // keep the last good data in case this fetch fails
    static Summary keep; keep = cur;
    static float kSg[NB], kT[NB]; static uint16_t knSg[NB], knT[NB];
    static Brew kOthers[4]; static int kN; static time_t kWs, kWe;
    memcpy(kSg, sumSg, sizeof(kSg)); memcpy(kT, sumT, sizeof(kT));
    memcpy(knSg, nSg, sizeof(knSg)); memcpy(knT, nT, sizeof(knT));
    memcpy(kOthers, others, sizeof(kOthers)); kN = nOthers; kWs = winStart; kWe = winEnd;

    setProblem("", "");
    bool ok = WiFi.status() == WL_CONNECTED && (isBrewfather() ? brewfatherFetch() : raptFetch());
    if (ok) decideReady();
    else {
      if (WiFi.status() != WL_CONNECTED) setProblem("Wi-Fi dropped - reconnecting", "Check your router. Trying again in a minute.");
      if (keep.lastT) {
        cur = keep;
        memcpy(sumSg, kSg, sizeof(kSg)); memcpy(sumT, kT, sizeof(kT));
        memcpy(nSg, knSg, sizeof(knSg)); memcpy(nT, knT, sizeof(knT));
        memcpy(others, kOthers, sizeof(kOthers)); nOthers = kN; winStart = kWs; winEnd = kWe;
      }
      lastFetch = now - (unsigned long)cfg.refreshMin * 60000UL + 60000UL;   // retry in 1 min
    }
    if (ok || keep.lastT) drawCurrentPage(); else drawProblem();
    lastMinute = now;
    pageSince = now;
    return;
  }

  // Rotate pages
  if (cur.lastT && nPages > 1 && now - pageSince > (unsigned long)cfg.pageSecs * 1000UL) {
    pageSince = now;
    pageIndex = (pageIndex + 1) % nPages;
    drawCurrentPage();
  }

  // Every minute: night dimming, and keep "12m ago" / the clock current
  if (now - lastMinute > 60000UL) {
    lastMinute = now;
    updateBacklight();
    if (cur.lastT) {
      if (currentPage() == 0) drawStatusLine();
      else drawCurrentPage();
    }
  }
#endif
}
