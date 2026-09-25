#!/system/bin/sh
MODDIR=${0%/*}
OIS_EXPECTED=2046ad98cfdb823a855ff6b81ebdee82e8e2d4540be0747322e1f6e5b6fc7efb
VAF_EXPECTED=cf9116660b5ea5be7cbb297be7dddb3334a486c1e886d752bc72cb8d4f8edaf8
META_EXPECTED=2bc7dbc8e88bad452e60b6ed594310fb72ebb2100ff7f6740a8b7a1d3fac5154
if [ "$(getprop ro.product.vendor.device)" != PD2405 ]; then
  echo 'Refusing: device is not PD2405' >> "$MODDIR/controller.log"
  exit 1
fi

start_daemon() {
  DAEMON_BIN="$1"
  DAEMON_PIDFILE="$2"
  DAEMON_LOG="$3"
  DAEMON_ARG="$4"
  if [ -f "$DAEMON_PIDFILE" ]; then
    DAEMON_OLD=$(cat "$DAEMON_PIDFILE")
    if kill -0 "$DAEMON_OLD" 2>/dev/null &&
       [ "$(readlink "/proc/$DAEMON_OLD/exe" 2>/dev/null)" = "$DAEMON_BIN" ]; then
      DAEMON_OLD_MNT=$(readlink "/proc/$DAEMON_OLD/ns/mnt" 2>/dev/null)
      DAEMON_INIT_MNT=$(readlink /proc/1/ns/mnt 2>/dev/null)
      if [ -n "$DAEMON_OLD_MNT" ] && [ "$DAEMON_OLD_MNT" = "$DAEMON_INIT_MNT" ]; then
        return 0
      fi
      echo "Restarting stale daemon from mount namespace $DAEMON_OLD_MNT" >> "$DAEMON_LOG"
      kill -TERM "$DAEMON_OLD" 2>/dev/null
      DAEMON_COUNT=0
      while kill -0 "$DAEMON_OLD" 2>/dev/null && [ "$DAEMON_COUNT" -lt 20 ]; do
        sleep 0.1
        DAEMON_COUNT=$((DAEMON_COUNT + 1))
      done
      if kill -0 "$DAEMON_OLD" 2>/dev/null; then
        echo 'Refusing: stale daemon did not exit' >> "$DAEMON_LOG"
        return 1
      fi
    fi
  fi
  chmod 700 "$DAEMON_BIN"
  echo "Starting $DAEMON_BIN in init mount namespace $(readlink /proc/1/ns/mnt)" >> "$DAEMON_LOG"
  nsenter -t 1 -m -- "$DAEMON_BIN" "$DAEMON_ARG" >> "$DAEMON_LOG" 2>&1 &
  echo $! > "$DAEMON_PIDFILE"
}

if [ "$(sha256sum /vendor/lib64/mt6991/libcam.hal3a.oisdrv.so | cut -d ' ' -f 1)" = "$OIS_EXPECTED" ]; then
  start_daemon "$MODDIR/ois_gain_controller" "$MODDIR/controller.pid" \
      "$MODDIR/controller.log" --apply
else
  echo 'Refusing OIS: library hash changed' >> "$MODDIR/controller.log"
fi

if [ "$(sha256sum /vendor/lib64/libvivo.vaf.system.so | cut -d ' ' -f 1)" = "$VAF_EXPECTED" ] &&
   [ "$(sha256sum /vendor/lib64/libvivo.algo.metadata.so | cut -d ' ' -f 1)" = "$META_EXPECTED" ]; then
  chmod 600 "$MODDIR/watermark_hook.js"
  start_daemon "$MODDIR/watermark_controller" "$MODDIR/watermark_controller.pid" \
      "$MODDIR/watermark_controller.log" "$MODDIR/watermark_hook.js"
else
  echo 'Refusing watermark: library hash changed' >> "$MODDIR/watermark_controller.log"
fi
