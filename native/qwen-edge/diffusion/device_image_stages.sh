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

qwen_receipt_version=2

qwen_stage_number() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ "${#1}" -le 10 ] && [ "$1" -ge "$2" ] 2>/dev/null && [ "$1" -le "$3" ] 2>/dev/null
}

qwen_stage_distinct() {
    case "$1" in "$2"|"$2"/*) return 1 ;; esac
    case "$2" in "$1"/*) return 1 ;; esac
}

qwen_file_bytes() {
    qwen_regular_file "$1" && [ "$(stat -c %s "$1" 2>/dev/null)" = "$2" ]
}

qwen_validate_inputs() {
    [ "$#" = 6 ] || return 1
    stage=$1; directory=$2; source_directory=$3; budget=$6
    qwen_physical_directory "$directory" && qwen_physical_directory "$source_directory" || return 1
    qwen_stage_distinct "$directory" "$source_directory" || return 1
    qwen_regular_file "$directory/image_smoke" && [ -x "$directory/image_smoke" ] || return 1
    qwen_regular_file "$directory/image_guardian.sh" || return 1
    qwen_regular_file "$source_directory/vae.safetensors" || return 1
    case "$stage" in
        sample)
            tokens=$4; seed=$5; checkpoint_path=$directory/checkpoint.qwenlatent
            qwen_stage_number "$tokens" 1 498 && qwen_stage_number "$seed" 0 2147483647 && qwen_stage_number "$budget" 1 3900 || return 1
            qwen_regular_file "$source_directory/dit.gguf" || return 1
            qwen_file_bytes "$source_directory/conditions.f32" "$((tokens * 16384))" || return 1
            ;;
        decode)
            checkpoint_directory=$4; decode_mode=$5
            qwen_physical_directory "$checkpoint_directory" && qwen_stage_distinct "$directory" "$checkpoint_directory" || return 1
            case "$decode_mode" in full|tile256) ;; *) return 1 ;; esac
            qwen_stage_number "$budget" 1 900 || return 1
            checkpoint_path=$checkpoint_directory/checkpoint.qwenlatent
            qwen_file_bytes "$checkpoint_path" 262288 || return 1
            tokens=from_checkpoint; seed=from_checkpoint
            ;;
        *) return 1 ;;
    esac
}

qwen_worker_inputs() { qwen_validate_inputs "$@"; }
qwen_supervisor_inputs() { qwen_validate_inputs "$@"; }
qwen_invoke_native() { exec "$@"; }

qwen_stage_native() {
    case "$stage" in
        sample) qwen_invoke_native "$directory/image_smoke" "$source_directory/dit.gguf" "$source_directory/vae.safetensors" "$source_directory/conditions.f32" "$tokens" "$seed" "$directory/output.png" --sample-only "$directory/checkpoint.qwenlatent" ;;
        decode) qwen_invoke_native "$directory/image_smoke" "$source_directory/vae.safetensors" "$decode_mode" "$checkpoint_directory/checkpoint.qwenlatent" "$directory/output.png" ;;
        *) return 73 ;;
    esac
}

qwen_native_command() { qwen_stage_native; }

qwen_receipt_extension() {
    printf 'stage=%s\nsource_directory=%s\ncheckpoint_path=%s\ntokens=%s\nseed=%s\n' \
        "$stage" "$source_directory" "$checkpoint_path" "$tokens" "$seed" || return 1
    [ "$stage" != decode ] || printf 'decode_mode=%s\n' "$decode_mode" || return 1
    [ "$1" != native ] || printf 'budget_seconds=%s\n' "$budget"
}

if [ "${1-}" = __library ]; then return 0; fi
operation=${1-}; shift 2>/dev/null || exit 73
case "$operation" in
    supervise) qwen_supervise "$@" ;;
    worker) qwen_worker "$@" ;;
    *) printf 'GUARD_REFUSED unknown operation\n'; exit 73 ;;
esac
