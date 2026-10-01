#!/bin/bash

set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
    echo "usage: $0 <serial> <version-label> <account-count> <sample-count> [cooldown-seconds]" >&2
    exit 2
fi

serial="$1"
version_label="$2"
account_count="$3"
sample_count="$4"
cooldown_seconds="${5:-0}"
package_name="com.salesforce.androidsdk.tests"
runner="${package_name}/androidx.test.runner.AndroidJUnitRunner"
activity="${package_name}/com.salesforce.androidsdk.MainActivity"
benchmark_class="com.salesforce.androidsdk.performance.AccountPathBenchmarkTest#seedForLaunch"

run_once() {
    local output
    local total_time
    local launch_state

    # Synthetic credentials are intentionally local-only. Re-seed each iteration so
    # no background SDK behavior from a prior launch can invalidate the next sample.
    adb -s "$serial" shell am instrument -w -r \
        -e accounts "$account_count" \
        -e class "$benchmark_class" \
        "$runner" >/dev/null
    # storeCurrentUserInfo() uses SharedPreferences.apply(); allow its async disk
    # write to finish before killing the seeding process.
    sleep 5
    adb -s "$serial" shell am force-stop --user 0 "$package_name"
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if [[ -z "$(adb -s "$serial" shell pidof "$package_name")" ]]; then
            break
        fi
        sleep 0.05
    done
    sleep 0.5
    if [[ -n "$(adb -s "$serial" shell pidof "$package_name")" ]]; then
        printf 'benchmark process remained alive before cold launch\n' >&2
        return 1
    fi
    output="$(adb -s "$serial" shell am start -W -n "$activity")"
    total_time="$(printf '%s\n' "$output" | awk -F': ' '/^TotalTime:/{print $2; exit}')"
    launch_state="$(printf '%s\n' "$output" | awk -F': ' '/^LaunchState:/{print $2; exit}')"
    if [[ -z "$total_time" || "$launch_state" != "COLD" ]]; then
        printf 'unexpected am start output:\n%s\n' "$output" >&2
        return 1
    fi
    if [[ "$cooldown_seconds" != "0" ]]; then
        sleep "$cooldown_seconds"
    fi
    printf '%s\n' "$total_time"
}

# Discard three runs so each cell starts after equivalent code/data warm-up.
for _ in 1 2 3; do
    run_once >/dev/null
done

values=()
for ((sample = 1; sample <= sample_count; sample++)); do
    value="$(run_once)"
    values+=("$value")
    printf 'COLD_SAMPLE version=%s accounts=%s sample=%d total_time_ms=%s\n' \
        "$version_label" "$account_count" "$sample" "$value"
done

sorted_values="$(printf '%s\n' "${values[@]}" | sort -n)"
p50_index=$(( (50 * (sample_count - 1)) / 100 ))
p95_index=$(( (95 * (sample_count - 1)) / 100 ))
mean="$(printf '%s\n' "${values[@]}" | awk '{sum += $1} END {printf "%.3f", sum / NR}')"
p50="$(printf '%s\n' "$sorted_values" | sed -n "$((p50_index + 1))p")"
p95="$(printf '%s\n' "$sorted_values" | sed -n "$((p95_index + 1))p")"
minimum="$(printf '%s\n' "$sorted_values" | head -n 1)"
maximum="$(printf '%s\n' "$sorted_values" | tail -n 1)"

printf 'COLD_RESULT version=%s accounts=%s samples=%s mean_ms=%s p50_ms=%s p95_ms=%s min_ms=%s max_ms=%s\n' \
    "$version_label" "$account_count" "$sample_count" "$mean" \
    "$p50" "$p95" "$minimum" "$maximum"
