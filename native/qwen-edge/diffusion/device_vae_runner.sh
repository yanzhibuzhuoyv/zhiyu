#!/system/bin/sh
# Research-only fixed entrypoint; upload common beside this file, never overwrite old jobs.
set -C
umask 077
qwen_common_sha='7be5698baf704fcb42cb2846c4c502744c283ef71217722fac9bd12ca66bc47a'
if [ "${1-}" = __library ] && [ -n "${BASH_VERSION-}" ]; then
    qwen_load_directory=${BASH_SOURCE%/*}
else
    qwen_load_directory=${0%/*}
    case "$qwen_load_directory" in /data/local/tmp/zhiyu_qwen_research_*) ;; *) exit 73 ;; esac
    qwen_load_suffix=${qwen_load_directory#/data/local/tmp/zhiyu_qwen_research_}
    case "$qwen_load_suffix" in ''|*[!A-Za-z0-9_]*) exit 73 ;; esac
    qwen_load_physical=$(cd "$qwen_load_directory" 2>/dev/null && pwd -P) || exit 73
    [ "$qwen_load_physical" = "$qwen_load_directory" ] && [ ! -L "$qwen_load_directory" ] || exit 73
fi
qwen_common_file=$qwen_load_directory/device_guard_common.sh
[ -f "$qwen_common_file" ] && [ ! -L "$qwen_common_file" ] || { return 73 2>/dev/null || exit 73; }
qwen_actual_sha=$(sha256sum "$qwen_common_file" 2>/dev/null | awk '{print $1}')
[ "$qwen_actual_sha" = "$qwen_common_sha" ] || { return 73 2>/dev/null || exit 73; }
. "$qwen_common_file" || { return 73 2>/dev/null || exit 73; }

qwen_receipt_version=1

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

qwen_worker_inputs() {
    [ "$#" = 4 ] && qwen_validate_inputs "$@" 1 || return 1
    directory=$1; vae=$2; mode=$3; pattern=$4; stage=vae_probe
}

qwen_supervisor_inputs() {
    directory=$1; vae=$2; mode=$3; pattern=$4; budget=$5; stage=vae_probe
}

qwen_native_command() {
    exec "$directory/image_smoke" "$vae" "$mode" "$pattern" "$directory/output.png"
}

qwen_receipt_extension() { :; }

if [ "${1-}" = __library ]; then return 0; fi
operation=${1-}; shift 2>/dev/null || exit 73
case "$operation" in
    supervise) qwen_supervise "$@" ;;
    worker) qwen_worker "$@" ;;
    *) printf 'GUARD_REFUSED unknown operation\n'; exit 73 ;;
esac
