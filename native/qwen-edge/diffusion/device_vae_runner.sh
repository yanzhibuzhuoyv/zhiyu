#!/system/bin/sh
# Research VAE-only guardian. Never send a negative PID/process-group signal.
# The host may source __library for synthetic tests; it must not run supervise.
set -C
umask 077

qwen_owned_directory() {
    case "$1" in /data/local/tmp/zhiyu_qwen_research_*) ;; *) return 1 ;; esac
    suffix=${1#/data/local/tmp/zhiyu_qwen_research_}
    case "$suffix" in ''|*[!A-Za-z0-9_]*) return 1 ;; esac
}

qwen_physical_directory() {
    qwen_owned_directory "$1" || return 1
    physical=$(cd "$1" 2>/dev/null && pwd -P) || return 1
    [ "$physical" = "$1" ] && [ ! -L "$1" ]
}

qwen_number() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ "$1" -gt 0 ] 2>/dev/null
}

qwen_identity() {
    qwen_number "$1" || return 1
    [ -d "/proc/$1" ] || return 2
    stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    argv=$(tr '\000' '\n' < "/proc/$1/cmdline" 2>/dev/null | sed -n '1p')
    [ -n "$argv" ] || return 1
    values=$(printf '%s\n' "$stat" | sed 's/^.*) //' | awk '{print $20, $3, $4}')
    set -- "$1" $values "$argv"
    [ "$#" = 5 ] || return 1
    qwen_number "$1" && qwen_number "$2" && qwen_number "$3" && qwen_number "$4" || return 1
    printf '%s %s %s %s %s\n' "$1" "$2" "$3" "$4" "$5"
}

qwen_match_identity() {
    [ "$1" = "$2" ] || return 1
    set -- $1
    [ "$#" = 5 ] || return 1
    qwen_number "$1" && [ "$1" -gt 1 ] && qwen_number "$2" || return 1
    qwen_number "$3" && qwen_number "$4" || return 1
    case "$5" in /data/local/tmp/zhiyu_qwen_research_*/image_smoke) ;; *) return 1 ;; esac
    qwen_owned_directory "${5%/image_smoke}"
}

qwen_wait_gone() {
    expected=$1
    set -- $expected
    target=$1
    tries=0
    while [ "$tries" -lt 50 ]; do
        current=$(qwen_identity "$target"); result=$?
        [ "$result" = 2 ] && return 0
        [ "$result" = 0 ] && qwen_match_identity "$expected" "$current" || return 1
        sleep 0.1
        tries=$((tries + 1))
    done
    return 3
}

qwen_stop_native() {
    expected=$1
    set -- $expected
    target=$1
    current=$(qwen_identity "$target"); result=$?
    [ "$result" = 2 ] && return 0
    [ "$result" = 0 ] && qwen_match_identity "$expected" "$current" || return 1
    kill -TERM "$target" || return 1
    qwen_wait_gone "$expected"; result=$?
    [ "$result" = 0 ] && return 0
    [ "$result" = 3 ] || return 1
    current=$(qwen_identity "$target") || return 1
    qwen_match_identity "$expected" "$current" || return 1
    kill -KILL "$target" || return 1
    qwen_wait_gone "$expected"
}

qwen_hal_battery() {
    awk '
        /Current temperatures from HAL:/ { active=1; next }
        /Current cooling devices from HAL:/ { active=0 }
        active && /mType=2[,}]/ && /mName=battery[^,}]*[,}]/ {
            line=$0; sub(/^.*mValue=/,"",line); sub(/[,}].*$/,"",line)
            if (line !~ /^[0-9]+(\.[0-9]+)?$/ || line+0 < 0 || line+0 > 100) exit 1
            if (!found || line+0 > maximum+0) maximum=line
            found=1
        }
        END { if (found) print maximum; else exit 1 }
    '
}

qwen_stop_reason() {
    awk -v battery="$1" -v thermal="$2" -v available="$3" 'BEGIN {
        if (battery !~ /^[0-9]+(\.[0-9]+)?$/ || battery+0>100 || thermal !~ /^[0-9]+$/ || thermal+0>6 || available !~ /^[0-9]+$/) print "readings_unknown"
        else if (battery+0>=44) print "battery_44c"
        else if (thermal+0>=3) print "thermal_severe"
        else if (available+0<393216) print "memory_low"
        else print "safe"
    }'
}

qwen_metrics() {
    snapshot=$(setsid -w timeout -k 1 5 dumpsys thermalservice 2>/dev/null) || { printf 'readings_unknown\n'; return; }
    battery=$(printf '%s\n' "$snapshot" | qwen_hal_battery) || { printf 'readings_unknown\n'; return; }
    thermal=$(printf '%s\n' "$snapshot" | sed -n 's/^.*Thermal Status: *\([0-9][0-9]*\).*$/\1/p')
    available=$(awk '$1=="MemAvailable:" { print $2 }' /proc/meminfo 2>/dev/null)
    printf 'GUARD_METRIC battery_c=%s thermal=%s available_kib=%s\n' "$battery" "$thermal" "$available" >&2
    qwen_stop_reason "$battery" "$thermal" "$available"
}

qwen_validate_inputs() {
    [ "$#" = 5 ] || return 1
    qwen_physical_directory "$1" || return 1
    case "$2" in */vae.safetensors) ;; *) return 1 ;; esac
    qwen_physical_directory "${2%/vae.safetensors}" || return 1
    [ -f "$2" ] && [ ! -L "$2" ] || return 1
    case "$3" in full|tile256) ;; *) return 1 ;; esac
    case "$4" in zero|sine) ;; *) return 1 ;; esac
    qwen_number "$5" && [ "$5" -le 900 ] || return 1
    [ -x "$1/image_smoke" ] && [ ! -L "$1/image_smoke" ] || return 1
    [ -f "$1/vae_guardian.sh" ] && [ ! -L "$1/vae_guardian.sh" ]
}

qwen_reserve_files() {
    for name in run.lock output.png native.log native.identity guardian.identity native.receipt guardian.receipt stop.request; do
        [ ! -e "$1/$name" ] && [ ! -L "$1/$name" ] || return 1
    done
    mkdir "$1/run.lock"
}

qwen_native_receipt() {
    set -- $native_identity
    printf 'version=1\nkind=native\ndirectory=%s\nnative_pid=%s\nnative_start=%s\nnative_pgid=%s\nnative_session=%s\nargv0=%s\nnative_wait_code=%s\nwait_confirmed=%s\nterm_interrupted=%s\ncomplete=1\n' \
        "$directory" "$1" "$2" "$3" "$4" "$5" "$native_code" "$wait_confirmed" "$term_interrupted" > "$directory/native.receipt"
}

qwen_capture_native() {
    attempt=0
    while [ "$attempt" -lt 20 ]; do
        candidate=$(qwen_identity "$native_pid"); result=$?
        if [ "$result" = 0 ]; then
            set -- $candidate
            if [ "$5" = "$directory/image_smoke" ] && [ "$3" = "$worker_group" ] && [ "$4" = "$worker_session" ]; then
                native_identity=$candidate
                printf '%s\n' "$native_identity" > "$directory/native.identity"
                return
            fi
        fi
        [ "$result" = 2 ] && return 1
        sleep 0.05
        attempt=$((attempt + 1))
    done
    return 1
}

qwen_worker_isolated() {
    worker_identity=$(qwen_identity "$$") || return 1
    guardian_identity=$(cat "$directory/guardian.identity") || return 1
    set -- $guardian_identity
    [ "$#" = 5 ] || return 1
    current_guardian=$(qwen_identity "$1") || return 1
    [ "$current_guardian" = "$guardian_identity" ] || return 1
    guard_group=$3; guard_session=$4
    set -- $worker_identity
    worker_group=$3; worker_session=$4
    [ "$1" = "$3" ] && [ "$3" != "$guard_group" ] && [ "$4" != "$guard_session" ]
}

qwen_worker_wait() {
    wait_confirmed=1
    wait "$native_pid"; native_code=$?
    if [ "$term_interrupted" = 1 ]; then
        wait "$native_pid"; second=$?
        if [ "$second" = 127 ]; then wait_confirmed=0; else native_code=$second; fi
    fi
    qwen_native_receipt || return 1
    return "$native_code"
}

qwen_worker() {
    [ "$#" = 4 ] && qwen_validate_inputs "$@" 1 || return 73
    directory=$1; vae=$2; mode=$3; pattern=$4
    qwen_worker_isolated || { printf 'WORKER_REFUSED unsafe group/session\n'; return 74; }
    reason=$(qwen_metrics)
    [ "$reason" = safe ] || { printf 'WORKER_REFUSED unsafe readings: %s\n' "$reason"; return 76; }
    [ ! -e "$directory/stop.request" ] && [ ! -L "$directory/stop.request" ] || return 76
    term_interrupted=0
    trap 'term_interrupted=1' TERM
    "$directory/image_smoke" "$vae" "$mode" "$pattern" "$directory/output.png" &
    native_pid=$!
    qwen_capture_native || { wait "$native_pid"; printf 'WORKER_IDENTITY_UNCONFIRMED\n'; return 75; }
    qwen_worker_wait
}

qwen_guardian_receipt() {
    set -- $guardian_identity
    printf 'version=1\nkind=guardian\ndirectory=%s\nguard_pid=%s\nguard_start=%s\nguard_pgid=%s\nguard_session=%s\ntimeout_wait_code=%s\nbudget_seconds=%s\nstop_reason=%s\ncleanup=%s\ncomplete=1\n' \
        "$directory" "$1" "$2" "$3" "$4" "$timeout_code" "$budget" "$stop_reason" "$cleanup" > "$directory/guardian.receipt"
}

qwen_cleanup() {
    expected=$(cat "$directory/native.identity" 2>/dev/null) || { cleanup=unconfirmed; return 1; }
    if qwen_stop_native "$expected"; then cleanup=confirmed; else cleanup=unconfirmed; fi
}

qwen_request_stop() {
    if [ ! -e "$directory/stop.request" ]; then
        printf '%s\n' "$stop_reason" > "$directory/stop.request" || { cleanup=unconfirmed; return 1; }
    fi
    attempt=0
    while [ ! -f "$directory/native.identity" ] && [ "$attempt" -lt 20 ]; do
        sleep 0.1
        attempt=$((attempt + 1))
    done
    qwen_cleanup
}

qwen_monitor() {
    while kill -0 "$timeout_pid" 2>/dev/null; do
        [ "$stop_reason" != guardian_signal ] || break
        reason=$(qwen_metrics)
        printf 'GUARD_METRIC time=%s reason=%s\n' "$(date +%s)" "$reason"
        if [ "$reason" != safe ]; then
            stop_reason=$reason
            qwen_request_stop
            break
        fi
        sleep 1
    done
    [ "$stop_reason" != guardian_signal ] || qwen_request_stop
}

qwen_after_wait() {
    if [ "$timeout_code" = 124 ] || [ "$timeout_code" = 137 ]; then
        [ "$stop_reason" != finished ] || stop_reason=timeout
    fi
    if [ -f "$directory/native.identity" ]; then
        expected=$(cat "$directory/native.identity")
        set -- $expected
        current=$(qwen_identity "$1"); result=$?
        if [ "$result" != 2 ]; then
            [ "$stop_reason" != finished ] || stop_reason=identity_unknown
            qwen_cleanup
        fi
    elif [ "$timeout_code" != 0 ]; then
        cleanup=unconfirmed
    fi
    qwen_guardian_receipt
}

qwen_supervise() {
    qwen_validate_inputs "$@" || { printf 'GUARD_REFUSED invalid inputs\n'; return 73; }
    for command in timeout setsid nohup awk sed grep date sleep cat tr dumpsys; do
        command -v "$command" >/dev/null || { printf 'GUARD_REFUSED missing %s\n' "$command"; return 73; }
    done
    directory=$1; vae=$2; mode=$3; pattern=$4; budget=$5
    qwen_reserve_files "$directory" || { printf 'GUARD_REFUSED files already exist\n'; return 73; }
    guardian_identity=$(qwen_identity "$$") || return 74
    printf '%s\n' "$guardian_identity" > "$directory/guardian.identity" || return 74
    stop_reason=finished; cleanup=not_needed
    trap 'stop_reason=guardian_signal' TERM INT
    trap '' HUP
    setsid -w timeout -k 5 "$budget" /system/bin/sh "$directory/vae_guardian.sh" worker \
        "$directory" "$vae" "$mode" "$pattern" > "$directory/native.log" 2>&1 &
    timeout_pid=$!
    qwen_monitor
    wait "$timeout_pid"; timeout_code=$?
    qwen_after_wait
}

if [ "${1-}" = __library ]; then return 0; fi
operation=${1-}; shift 2>/dev/null || exit 73
case "$operation" in
    supervise) qwen_supervise "$@" ;;
    worker) qwen_worker "$@" ;;
    *) printf 'GUARD_REFUSED unknown operation\n'; exit 73 ;;
esac
