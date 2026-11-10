#!/system/bin/sh
# Device acceptance only observes charging. Persistent writes can survive process
# termination, and backing up an already contaminated config preserves test limits.
# Exercise policy changes and restoration against temporary nodes in host tests.
M=/data/adb/hypercraft/runtime/adclean.sh
echo 'PROBE|charge|read-only'
sh "$M" charge status
