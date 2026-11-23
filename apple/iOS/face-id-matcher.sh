#!/bin/sh
# Answers Face ID prompts in a simulator with a matching face, when the UI test asks for one.
# Used by UnlockFlowTests.testFaceIDQuickUnlock (see apple/README.md).
#
#   apple/iOS/face-id-matcher.sh <simulator id>
#
# The test (run with TEST_RUNNER_FACE_ID_MATCHER=1) asks by creating `face-id-match` in its own
# temporary folder, which is inside the simulator's folder on this Mac; this sends the match and
# removes the file.
set -eu
device=$1
apps="$HOME/Library/Developer/CoreSimulator/Devices/$device/data/Containers/Data/Application"
while true; do
    for request in "$apps"/*/tmp/face-id-match; do
        [ -f "$request" ] || continue
        rm -f "$request"
        sleep 1 # let the prompt finish appearing
        xcrun simctl spawn "$device" notifyutil -p com.apple.BiometricKit_Sim.pearl.match
        echo "$(date +%T) matched"
    done
    sleep 0.3
done
