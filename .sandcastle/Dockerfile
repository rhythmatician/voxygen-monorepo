FROM node:22-bookworm

# Install system dependencies (python3 + pip for graphify code-only updates)
RUN apt-get update && apt-get install -y git curl jq python3 python3-pip python3-venv wget gnupg && rm -rf /var/lib/apt/lists/*

# Install Temurin 25 JDK (required for java/gradlew lint compileJava compileClientJava — mirrors factory-ci.yml setup-java Temurin 25)
RUN mkdir -p /etc/apt/keyrings && wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" | tee /etc/apt/sources.list.d/adoptium.list > /dev/null && apt-get update && apt-get install -y temurin-25-jdk && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# Install graphify (code-only graph rebuild, no LLM needed) system-wide so sandcastle agents
# can run `graphify update .` on worktree startup. --break-system-packages is required on
# Debian bookworm where pip refuses system installs inside the base image.
RUN pip install --break-system-packages graphify 2>&1 | tail -5

# Install GitHub CLI
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
  | tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
  && apt-get update && apt-get install -y gh \
  && rm -rf /var/lib/apt/lists/*

# Build-args for UID/GID alignment: sandcastle docker build-image
# defaults these to the host user's UID/GID so image-built files
# and bind-mounted files share an owner without runtime chown.
ARG AGENT_UID=1000
ARG AGENT_GID=1000

# Rename the base image's "node" user to "agent" and align UID/GID.
RUN groupmod -o -g $AGENT_GID node && usermod -o -u $AGENT_UID -g $AGENT_GID -d /home/agent -m -l agent node
USER ${AGENT_UID}:${AGENT_GID}

# Install Muse CLI
RUN curl -fsSL https://dev.meta.ai/install.sh | bash

# Add Muse to PATH (install.sh writes to ~/.local/bin)
ENV PATH="/home/agent/.local/bin:$PATH"

WORKDIR /home/agent

# In worktree sandbox mode, Sandcastle bind-mounts the git worktree at /home/agent/workspace
# and overrides the working directory to /home/agent/workspace at container start.
# Structure your Dockerfile so that /home/agent/workspace can serve as the project root.
ENTRYPOINT ["sleep", "infinity"]
