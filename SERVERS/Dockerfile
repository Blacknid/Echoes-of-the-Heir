# ------------------------------------------------------------------
# Single image used by all three Michi servers (save / multiplayer / patch).
# Each compose service simply changes its working_dir and command.
# ------------------------------------------------------------------
FROM python:3.12-slim AS runtime

# OS deps: cryptography needs libffi + build essentials only at compile
# time on slim, but the wheel is prebuilt for 3.12 → just install runtime.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini \
 && rm -rf /var/lib/apt/lists/*

# Python deps shared by all three servers.
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
 && rm /tmp/requirements.txt

# All three server trees are copied in at build time so the image is
# self-contained (no source bind-mount needed). Mutable state is mounted
# as named volumes / bind mounts in docker-compose.yml.
WORKDIR /srv
COPY save_server        /srv/save_server
COPY multiplayer_server /srv/multiplayer_server
COPY patch_server       /srv/patch_server

# Ensure runtime dirs exist with permissive perms (compose may bind-mount
# host folders over them; this just guarantees they're there for the
# image-only run case).
RUN mkdir -p /srv/save_server/saves \
             /srv/patch_server/patches

# tini = PID 1 → clean Ctrl-C handling, no zombie processes.
ENTRYPOINT ["/usr/bin/tini", "--"]
# Default command is overridden per-service in docker-compose.yml.
CMD ["python", "-V"]
