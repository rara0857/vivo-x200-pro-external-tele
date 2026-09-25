#!/system/bin/sh
MODDIR=${0%/*}
stop_daemon() {
  BIN="$1"
  PIDFILE="$2"
  if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE")
    if [ "$(readlink "/proc/$PID/exe" 2>/dev/null)" = "$BIN" ]; then
      kill -TERM "$PID" 2>/dev/null
    fi
  fi
}
stop_daemon "$MODDIR/ois_gain_controller" "$MODDIR/controller.pid"
stop_daemon "$MODDIR/watermark_controller" "$MODDIR/watermark_controller.pid"
