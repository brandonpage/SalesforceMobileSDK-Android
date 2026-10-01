#!/bin/bash

set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
    echo "usage: $0 <serial> <version-label> <sample-count> <output-directory> [cooldown-seconds]" >&2
    exit 2
fi

serial="$1"
version_label="$2"
sample_count="$3"
output_directory="$4"
cooldown_seconds="${5:-20}"
package_name="com.mysalesforce.mycommunity.playgroundcommunity"
activity="${package_name}/com.mysalesforce.community.activity.CommunitiesWebviewActivity"
csv_path="${output_directory}/${version_label}.csv"
log_directory="${output_directory}/${version_label}-logs"

mkdir -p "$log_directory"
printf '%s\n' \
    'version,sample,app_cold_start_ms,native_load_ms,community_load_ms,splash_load_ms,am_total_time_ms,am_wait_time_ms,battery_temp_tenths_c,thermal_status' \
    >"$csv_path"

marker_duration() {
    local marker="$1"
    local log_path="$2"

    sed -n "s/.*OKAY ${marker}.*+\([0-9][0-9]*\)ms.*/\1/p" "$log_path" | tail -n 1
}

for ((sample = 1; sample <= sample_count; sample++)); do
    log_path="${log_directory}/sample-${sample}.log"
    adb -s "$serial" logcat -c
    adb -s "$serial" shell am force-stop --user 0 "$package_name"

    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if [[ -z "$(adb -s "$serial" shell pidof "$package_name")" ]]; then
            break
        fi
        sleep 0.05
    done
    if [[ -n "$(adb -s "$serial" shell pidof "$package_name")" ]]; then
        printf 'Publisher process remained alive before sample %s\n' "$sample" >&2
        exit 1
    fi

    sleep 1
    battery_temp="$(adb -s "$serial" shell dumpsys battery | awk -F: '/temperature/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}')"
    thermal_status="$(
        adb -s "$serial" shell dumpsys thermalservice 2>/dev/null |
            awk -F': ' '/^Thermal Status:/{gsub(/\r/, "", $2); print $2; exit}'
    )"
    thermal_status="${thermal_status:-unavailable}"
    launch_output="$(adb -s "$serial" shell am start -W -n "$activity")"
    total_time="$(printf '%s\n' "$launch_output" | awk -F': ' '/^TotalTime:/{print $2; exit}')"
    wait_time="$(printf '%s\n' "$launch_output" | awk -F': ' '/^WaitTime:/{print $2; exit}')"
    launch_state="$(printf '%s\n' "$launch_output" | awk -F': ' '/^LaunchState:/{print $2; exit}')"

    if [[ -z "$total_time" || "$launch_state" != "COLD" ]]; then
        printf 'Unexpected am start output for sample %s:\n%s\n' "$sample" "$launch_output" >&2
        exit 1
    fi

    deadline=$((SECONDS + 45))
    app_cold_start=""
    native_load=""
    while ((SECONDS < deadline)); do
        adb -s "$serial" logcat -d -v epoch -s System.out:I '*:S' >"$log_path"
        app_cold_start="$(marker_duration AppColdStart "$log_path")"
        native_load="$(marker_duration NativeLoad "$log_path")"
        if [[ -n "$app_cold_start" && -n "$native_load" ]]; then
            break
        fi
        sleep 0.25
    done

    if [[ -z "$app_cold_start" || -z "$native_load" ]]; then
        printf 'Timed out waiting for Publisher markers in sample %s\n' "$sample" >&2
        exit 1
    fi

    if ! rg -q 'OKAY AppColdStart\{[^}]*isUserLoggedIn=true' "$log_path"; then
        printf 'Sample %s was not an authenticated AppColdStart\n' "$sample" >&2
        exit 1
    fi

    community_load="$(marker_duration CommunityLoad "$log_path")"
    splash_load="$(marker_duration SplashLoad "$log_path")"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$version_label" "$sample" "$app_cold_start" "$native_load" \
        "$community_load" "$splash_load" "$total_time" "$wait_time" \
        "$battery_temp" "$thermal_status" >>"$csv_path"
    printf 'PUBLISHER_SAMPLE version=%s sample=%s app_cold_start_ms=%s native_load_ms=%s community_load_ms=%s splash_load_ms=%s am_total_time_ms=%s temp_tenths_c=%s thermal=%s\n' \
        "$version_label" "$sample" "$app_cold_start" "$native_load" \
        "$community_load" "$splash_load" "$total_time" "$battery_temp" "$thermal_status"

    if [[ "$cooldown_seconds" != "0" ]]; then
        sleep "$cooldown_seconds"
    fi
done

printf 'Wrote %s\n' "$csv_path"
