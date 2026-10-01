#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 4 || $# -gt 6 ]]; then
  echo "usage: $0 <device> <version> <apk> <output.csv> [iterations] [cooldown_seconds]" >&2
  exit 2
fi

device="$1"
version="$2"
apk="$3"
output="$4"
iterations="${5:-7}"
cooldown_seconds="${6:-10}"
package="com.example.sdkstartupbenchmark"
activity="$package/.MainActivity"

mkdir -p "$(dirname "$output")"
printf '%s\n' \
  'version,iteration,total_time_ms,wait_time_ms,sdk_init_ms,publisher_bootstrap_ms,client_ready_process_ms,resume_ms,battery_temp_c,thermal_status' \
  > "$output"

adb -s "$device" install -r "$apk" >/dev/null

# Publisher authenticates before it starts collecting cold-start samples, so its measured loop is
# not the first process launch after an APK update. Perform one unmeasured force-stop launch to put
# package update, dex loading, and profile state in the same precondition as the measured loop.
adb -s "$device" shell am force-stop "$package"
adb -s "$device" shell am start -W -n "$activity" >/dev/null
sleep "$cooldown_seconds"
adb -s "$device" shell am force-stop "$package"

marker_value() {
  local logs="$1"
  local event="$2"
  local key="$3"
  awk -v wanted_event="$event" -v wanted_key="$key" '
    index($0, "event=" wanted_event) {
      for (i = 1; i <= NF; i++) {
        split($i, pair, "=")
        if (pair[1] == wanted_key) value = pair[2]
      }
    }
    END { print value }
  ' <<< "$logs"
}

for ((iteration = 1; iteration <= iterations; iteration++)); do
  battery_temp_c="$(adb -s "$device" shell dumpsys battery | awk '/temperature:/ { printf "%.1f", $2 / 10; exit }')"
  thermal_status="$(adb -s "$device" shell dumpsys thermalservice | awk -F': ' '/Thermal Status:/ { print $2; exit }')"

  adb -s "$device" logcat -c
  adb -s "$device" shell am force-stop "$package"
  sleep 1

  start_output="$(adb -s "$device" shell am start -W -n "$activity")"
  sleep 1
  logs="$(adb -s "$device" logcat -d -v brief SDK_STARTUP_PERF:I AndroidRuntime:E '*:S')"

  total_time_ms="$(awk -F': ' '/^TotalTime:/ { print $2 }' <<< "$start_output")"
  wait_time_ms="$(awk -F': ' '/^WaitTime:/ { print $2 }' <<< "$start_output")"
  sdk_init_ms="$(marker_value "$logs" sdk_init_complete duration_ms)"
  publisher_bootstrap_ms="$(marker_value "$logs" publisher_session_bootstrap_complete duration_ms)"
  client_ready_process_ms="$(marker_value "$logs" activity_rest_client_ready process_elapsed_ms)"
  resume_ms="$(marker_value "$logs" activity_on_resume_returned duration_ms)"

  if [[ -z "$client_ready_process_ms" ]]; then
    echo "iteration $iteration failed: authenticated client marker missing" >&2
    echo "$logs" >&2
    exit 1
  fi

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$version" "$iteration" "$total_time_ms" "$wait_time_ms" "$sdk_init_ms" \
    "$publisher_bootstrap_ms" "$client_ready_process_ms" "$resume_ms" \
    "$battery_temp_c" "$thermal_status" \
    | tee -a "$output"

  if (( iteration < iterations )); then
    sleep "$cooldown_seconds"
  fi
done
