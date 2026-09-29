#!/usr/bin/env bash
#
# 把 vdsupport/src 下的「虚拟屏宿主」Java 源码编译为**裸 dex**。
#
# 为什么需要它：宿主由 Shizuku 以 shell/root 身份经 `app_process` 拉起，
# 而 `app_process` 的 CLASSPATH 吃掉的是 dex，不是 APK——
# 实测把 APK 当 CLASSPATH 会在 AndroidRuntime::startReg 的 FindClass 阶段直接 abort。
#
# 产物形状：<outDir>/virtualscreen/host.dex
# 它是 assets 源目录（在 build.gradle.kts 里并入 main），最终位于 APK 的
#   assets/virtualscreen/host.dex
# 注意 `unzip -p` 取它时要写全 `assets/` 前缀，而 AssetManager.open() 用无前缀路径
#   （`virtualscreen/host.dex`）——两者不能混用，写错会静默抽出 0 字节。
#
# 用法：scripts/build-vd-host.sh <androidSdkDir> <outDir>
#   androidSdkDir 需含 platforms/android-<N>/android.jar 与 build-tools/<v>/d8。
#   （两者都取版本号最大的一份，避免把 SDK 版本硬编码进来。）

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "用法: $0 <androidSdkDir> <outDir>" >&2
    exit 2
fi

SDK=$1
OUT=$2
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRC=$REPO_ROOT/vdsupport/src

if [[ ! -d $SRC ]]; then
    echo "找不到宿主源码目录: $SRC" >&2
    exit 1
fi

# 取版本号最大的一项（sort -V 做版本序，而非字典序）
pick_latest() {
    local dir=$1
    # shellcheck disable=SC2012
    ls -1 "$dir" 2>/dev/null | sort -V | tail -n 1
}

API=$(pick_latest "$SDK/platforms")
ANDROID_JAR="$SDK/platforms/$API/android.jar"
BT=$(pick_latest "$SDK/build-tools")
D8="$SDK/build-tools/$BT/d8"

[[ -f $ANDROID_JAR ]] || { echo "缺少 android.jar: $ANDROID_JAR" >&2; exit 1; }
[[ -x $D8 ]] || { echo "缺少或不可执行的 d8: $D8" >&2; exit 1; }

# 优先 JAVA_HOME（Gradle 下环境变量必然指向构建用 JDK，而它的 bin 不一定在 PATH 里），
# 其次从 java 反推，最后兜底到 PATH。
if [[ -n ${JAVAC:-} ]]; then
    :
elif [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/javac ]]; then
    JAVAC=$JAVA_HOME/bin/javac
elif command -v javac >/dev/null 2>&1; then
    JAVAC=$(command -v javac)
else
    JAVAC="$(dirname "$(command -v java)")/javac"
fi
[[ -x $JAVAC ]] || { echo "找不到 javac: $JAVAC（可用 JAVAC 环境变量覆盖）" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 输入排序后再喂给工具，避免 find 的目录序不定导致 dex 不逐字节可复现。
mapfile -t JAVA_SOURCES < <(find "$SRC" -name '*.java' | sort)
if [[ ${#JAVA_SOURCES[@]} -eq 0 ]]; then
    echo "宿主源码目录下没有 .java: $SRC" >&2
    exit 1
fi

# -source/-target 8 + -bootclasspath：经典做法。不能用 --release，它和 -bootclasspath 互斥。
# -Xlint:-options 压掉 JDK 21 对 "source value 8 is obsolete" 的提醒（不是错误）。
"$JAVAC" -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -d "$WORK/classes" \
    "${JAVA_SOURCES[@]}"

# min-api 30：宿主用到 VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL（API 29+），
# 且它只在设备上以 app_process 跑、不在 App 进程内，与 App 的 minSdk 26 无关。
# 输出目录必须先建好：d8 不会自己创建，目录不存在时报 "Invalid output: ..."。
mkdir -p "$WORK/dex"
# shellcheck disable=SC2046
"$D8" --min-api 30 --output "$WORK/dex" $(find "$WORK/classes" -name '*.class' | sort)

mkdir -p "$OUT/virtualscreen"
cp "$WORK/dex/classes.dex" "$OUT/virtualscreen/host.dex"

echo "宿主 dex 已生成: $OUT/virtualscreen/host.dex ($(stat -c%s "$OUT/virtualscreen/host.dex") 字节)"
