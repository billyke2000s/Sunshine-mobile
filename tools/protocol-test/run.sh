#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="$project_dir/build/protocol-test"
mkdir -p "$build_dir/classes"
cmake -S "$project_dir/app/src/main/cpp" -B "$build_dir/native" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir/native" -j2
javac -d "$build_dir/classes" "$project_dir"/app/src/main/java/dev/sunshinemobile/protocol/*.java "$project_dir/tools/protocol-test/HostHarness.java"
for name in host client; do
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$build_dir/$name.key" -out "$build_dir/$name.crt" -subj "/CN=$name" -days 1 >/dev/null 2>&1
    openssl pkcs8 -topk8 -nocrypt -in "$build_dir/$name.key" -outform DER -out "$build_dir/$name.pk8"
done
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1280x720:rate=30' -frames:v 1 -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -f h264 -y "$build_dir/frame.h264"
moonlight_dir="${MOONLIGHT_SOURCE:-$build_dir/moonlight}"
if [ ! -d "$moonlight_dir/src" ]; then git clone --recursive https://github.com/moonlight-stream/moonlight-common-c.git "$moonlight_dir"; fi
# Pin the independent client used to prove compatibility.
git -C "$moonlight_dir" checkout f900dd4767759c7b9d0e93bcea666b55c69ea62f
git -C "$moonlight_dir" submodule update --init --recursive
cmake -S "$moonlight_dir" -B "$build_dir/moonlight-build" -DCMAKE_BUILD_TYPE=Debug -DBUILD_SHARED_LIBS=OFF
cmake --build "$build_dir/moonlight-build" -j2
cc -std=c11 -D_DEFAULT_SOURCE -I"$moonlight_dir/src" "$project_dir/tools/protocol-test/moonlight_probe.c" "$build_dir/moonlight-build/libmoonlight-common-c.a" "$build_dir/moonlight-build/enet/libenet.a" -lopus -lcrypto -lpthread -o "$build_dir/probe"
start_host() {
    rm -f "$build_dir/ready"
    java -Djava.library.path="$build_dir/native" -cp "$build_dir/classes" HostHarness "$build_dir" > "$build_dir/host.log" 2>&1 &
    host_pid=$!
    for i in $(seq 1 50); do [ ! -f "$build_dir/ready" ] || return; sleep 0.1; done
    cat "$build_dir/host.log"; exit 1
}
trap 'kill "${host_pid:-0}" 2>/dev/null || true' EXIT
rm -f "$build_dir/client.der"
start_host
python3 "$project_dir/tools/protocol-test/client.py" "$build_dir" pair
kill "$host_pid"; wait "$host_pid" || true
start_host
for iteration in 1 2; do
    python3 "$project_dir/tools/protocol-test/client.py" "$build_dir" launch
    "$build_dir/probe" "$build_dir/received-$iteration.h264"
    ffmpeg -hide_banner -loglevel error -i "$build_dir/received-$iteration.h264" -f null -
    sleep 1
done
cat "$build_dir/host.log"
echo 'PASS: upstream Moonlight encrypted RTSP/ENet, H264 decode, Opus decode, reconnect after disconnect'
