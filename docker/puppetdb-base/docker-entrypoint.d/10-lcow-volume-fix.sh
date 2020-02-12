# Creating log path as a workaround for LCOW VOLUME bug
# https://github.com/moby/moby/issues/39892

mkdir -p "$LOGDIR"
chown "$USER":"$GROUP" "$LOGDIR"
