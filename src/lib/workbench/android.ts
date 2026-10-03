// Facts about Android and common libraries that the Overview uses to sort what it shows.
import type { ClassEntry } from "$lib/engine";

/** Well-known libraries by package prefix. The longest matching prefix wins. */
const LIBRARIES: [string, string][] = [
  ["android/support/", "Android Support Library"],
  ["androidx/compose/", "Jetpack Compose"],
  ["androidx/", "AndroidX"],
  ["kotlin/", "Kotlin"],
  ["kotlinx/coroutines/", "Kotlin Coroutines"],
  ["kotlinx/serialization/", "Kotlin Serialization"],
  ["kotlinx/", "Kotlin"],
  ["com/google/android/gms/", "Google Play services"],
  ["com/google/android/play/", "Google Play Core"],
  ["com/google/android/material/", "Material Components"],
  ["com/google/android/exoplayer2/", "ExoPlayer"],
  ["com/google/firebase/", "Firebase"],
  ["com/google/gson/", "Gson"],
  ["com/google/protobuf/", "Protocol Buffers"],
  ["com/google/common/", "Guava"],
  ["com/google/zxing/", "ZXing"],
  ["com/google/crypto/tink/", "Tink"],
  ["com/google/errorprone/", "Error Prone"],
  ["com/google/thirdparty/", "Guava"],
  ["okhttp3/", "OkHttp"],
  ["okio/", "Okio"],
  ["retrofit2/", "Retrofit"],
  ["com/squareup/moshi/", "Moshi"],
  ["com/squareup/picasso/", "Picasso"],
  ["com/squareup/", "Square"],
  ["io/reactivex/", "RxJava"],
  ["rx/", "RxJava"],
  ["dagger/", "Dagger"],
  ["javax/inject/", "javax.inject"],
  ["javax/annotation/", "javax.annotation"],
  ["com/facebook/react/", "React Native"],
  ["com/facebook/", "Facebook SDK"],
  ["com/fasterxml/jackson/", "Jackson"],
  ["org/apache/", "Apache"],
  ["org/json/", "org.json"],
  ["com/bumptech/glide/", "Glide"],
  ["io/flutter/", "Flutter"],
  ["org/jetbrains/", "JetBrains annotations"],
  ["org/intellij/", "JetBrains annotations"],
  ["coil/", "Coil"],
  ["io/ktor/", "Ktor"],
  ["org/slf4j/", "SLF4J"],
  ["ch/qos/logback/", "Logback"],
  ["org/bouncycastle/", "Bouncy Castle"],
  ["org/spongycastle/", "Spongy Castle"],
  ["com/airbnb/lottie/", "Lottie"],
  ["io/sentry/", "Sentry"],
  ["com/crashlytics/", "Crashlytics"],
  ["com/appsflyer/", "AppsFlyer"],
  ["com/adjust/", "Adjust"],
  ["com/unity3d/", "Unity"],
  ["org/greenrobot/", "greenrobot"],
  ["io/realm/", "Realm"],
  ["net/sqlcipher/", "SQLCipher"],
  ["org/chromium/", "Chromium"],
  ["org/objectweb/asm/", "ASM"],
  ["org/osmdroid/", "osmdroid"],
  ["org/joda/", "Joda-Time"],
  ["org/conscrypt/", "Conscrypt"],
  ["com/journeyapps/", "ZXing Android Embedded"],
  ["com/jakewharton/", "Jake Wharton libraries"],
  ["timber/", "Timber"],
  ["io/grpc/", "gRPC"],
  ["com/tencent/", "Tencent SDK"],
  ["com/huawei/", "Huawei SDK"],
  ["com/amazon/", "Amazon SDK"],
  ["com/microsoft/", "Microsoft SDK"],
  ["org/junit/", "JUnit"],
  ["junit/", "JUnit"],
  ["org/hamcrest/", "Hamcrest"],
  ["org/intellij/lang/", "JetBrains annotations"],
  ["_COROUTINE/", "Kotlin Coroutines"],
].sort((a, b) => b[0].length - a[0].length) as [string, string][];

export function libraryOf(classId: string): string | null {
  for (const [prefix, name] of LIBRARIES) if (classId.startsWith(prefix)) return name;
  return null;
}

/** One- or two-letter class names, or a class in a package of such names: what ProGuard and R8 leave. */
export function looksObfuscated(classId: string): boolean {
  const parts = classId.split("/");
  const name = parts[parts.length - 1].split("$")[0];
  if (/^[a-zA-Z]{1,2}\d?$/.test(name)) return true;
  return parts.length > 1 && parts.slice(0, -1).every((p) => p.length <= 2);
}

export interface Group {
  name: string;
  /** A package prefix to filter the tree with. */
  prefix: string;
  classes: number;
  library: boolean;
}

/**
 * Splits classes into libraries and the app's own packages (by their first two
 * or three segments). The app's own code comes first, since that's what you
 * came to read; each part biggest first.
 */
export function groupClasses(classes: ClassEntry[], appPackage?: string): { groups: Group[]; obfuscated: number } {
  const groups = new Map<string, Group>();
  let obfuscated = 0;
  const app = appPackage?.replaceAll(".", "/");
  for (const c of classes) {
    if (looksObfuscated(c.id)) obfuscated++;
    const lib = libraryOf(c.id);
    let key: string;
    let name: string;
    if (lib) {
      key = `lib:${lib}`;
      name = lib;
    } else if (app && (c.id.startsWith(app + "/") || c.id.slice(0, c.id.lastIndexOf("/")) === app)) {
      key = app;
      name = appPackage!;
    } else {
      const parts = c.id.split("/").slice(0, -1);
      const depth = parts.length > 2 && ["com", "org", "net", "io", "de", "me", "app"].includes(parts[0]) ? 3 : 2;
      key = parts.slice(0, depth).join("/") || "(default package)";
      name = key === "(default package)" ? key : key.replaceAll("/", ".");
    }
    const g = groups.get(key) ?? { name, prefix: lib ? "" : key, classes: 0, library: !!lib };
    if (lib && !g.prefix) g.prefix = LIBRARIES.find(([p]) => c.id.startsWith(p))![0].replace(/\/$/, "");
    g.classes++;
    groups.set(key, g);
  }
  const sorted = [...groups.values()].sort((a, b) => Number(a.library) - Number(b.library) || b.classes - a.classes);
  return { groups: sorted, obfuscated };
}

/** Runtime ("dangerous") permissions: the user is asked for each. */
const DANGEROUS = new Set(
  (
    "READ_CALENDAR WRITE_CALENDAR CAMERA READ_CONTACTS WRITE_CONTACTS GET_ACCOUNTS ACCESS_FINE_LOCATION " +
    "ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION RECORD_AUDIO READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE " +
    "ANSWER_PHONE_CALLS READ_CALL_LOG WRITE_CALL_LOG ADD_VOICEMAIL USE_SIP PROCESS_OUTGOING_CALLS BODY_SENSORS " +
    "BODY_SENSORS_BACKGROUND ACTIVITY_RECOGNITION SEND_SMS RECEIVE_SMS READ_SMS RECEIVE_WAP_PUSH RECEIVE_MMS " +
    "READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE ACCESS_MEDIA_LOCATION READ_MEDIA_IMAGES READ_MEDIA_VIDEO " +
    "READ_MEDIA_AUDIO READ_MEDIA_VISUAL_USER_SELECTED POST_NOTIFICATIONS NEARBY_WIFI_DEVICES BLUETOOTH_SCAN " +
    "BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE UWB_RANGING"
  ).split(" "),
);

/** Permissions granted only through a settings screen or to system apps; worth a look in any app. */
const SPECIAL = new Set(
  (
    "SYSTEM_ALERT_WINDOW WRITE_SETTINGS MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES INSTALL_PACKAGES " +
    "DELETE_PACKAGES PACKAGE_USAGE_STATS QUERY_ALL_PACKAGES SCHEDULE_EXACT_ALARM READ_LOGS BIND_ACCESSIBILITY_SERVICE " +
    "BIND_DEVICE_ADMIN BIND_NOTIFICATION_LISTENER_SERVICE BIND_VPN_SERVICE MANAGE_ACCOUNTS READ_PRIVILEGED_PHONE_STATE " +
    "WRITE_SECURE_SETTINGS CHANGE_COMPONENT_ENABLED_STATE REQUEST_IGNORE_BATTERY_OPTIMIZATIONS USE_FULL_SCREEN_INTENT " +
    "MANAGE_OWN_CALLS"
  ).split(" "),
);

export type PermissionLevel = "dangerous" | "special" | "normal" | "custom";

export function permissionLevel(name: string): PermissionLevel {
  const short = name.startsWith("android.permission.") ? name.slice(19) : null;
  if (short === null) return "custom";
  if (DANGEROUS.has(short)) return "dangerous";
  if (SPECIAL.has(short)) return "special";
  return "normal";
}

/** `android.permission.CAMERA` reads better as `CAMERA`; custom ones keep their full name. */
export function permissionLabel(name: string): string {
  return name.startsWith("android.permission.") ? name.slice(19) : name;
}

/** SDK level to Android version, for the levels apps actually target. */
const ANDROID_VERSIONS: Record<number, string> = {
  16: "4.1", 17: "4.2", 18: "4.3", 19: "4.4", 21: "5.0", 22: "5.1", 23: "6", 24: "7.0", 25: "7.1", 26: "8.0",
  27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16", 37: "17",
};

export function androidVersion(sdk: string | undefined): string | null {
  const v = sdk ? ANDROID_VERSIONS[Number(sdk)] : undefined;
  return v ? `Android ${v}` : null;
}
