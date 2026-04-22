#!/usr/bin/env bash
#
#  Installer for Fivetran Proxy Agent on Linux using Docker
#
#  For more information:
#     https://github.com/fivetran/proxy_agent
#
set -euo pipefail

# ── Helpers ──────────────────────────────────────────────────────────────────

die() { echo "ERROR: $*" >&2; exit 1; }

DEFAULT_FIVETRAN_API_URL="https://api.fivetran.com"

usage() {
    cat <<'EOF'
Usage:
  RUNTIME=docker ./install.sh <config.json> [--install-dir <dir>]
  TOKEN="<token>" RUNTIME=docker ./install.sh [--install-dir <dir>]

Options:
  --install-dir <dir>   Installation directory (default: ~/fivetran-proxy-agent)
EOF
    exit 1
}

# ── Early checks ─────────────────────────────────────────────────────────────

if [ "$(id -u)" -eq 0 ]; then
    die "This script should not be run as root. Please run as a regular user."
fi

if [ "${RUNTIME:-}" != "docker" ]; then
    die "RUNTIME must be set to 'docker' (got: '${RUNTIME:-}')."
fi

# ── Constants ────────────────────────────────────────────────────────────────

DEFAULT_INSTALL_DIR="$HOME/fivetran-proxy-agent"
MIN_DOCKER_VERSION="20.10.17"
MIN_RECOMMENDED_CPU_COUNT=2
MIN_RECOMMENDED_RAM_KB=2097152
MIN_RECOMMENDED_DISK_SPACE_MB=5120
AGENT_SCRIPT="proxy-agent-manager.sh"
AGENT_SCRIPT_URL="https://raw.githubusercontent.com/fivetran/proxy_agent/main/proxy-agent-manager.sh"
REGISTRY_TAGS_URL="https://us-docker.pkg.dev/v2/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent/tags/list"

WARNINGS=()
ERRORS=()

# ── Validation ───────────────────────────────────────────────────────────────

check_dependencies() {
    if ! command -v curl >/dev/null 2>&1; then
        ERRORS+=("Required dependency 'curl' is not installed or not found in PATH. Please install curl and re-run this installer.")
    fi
}

check_docker_version() {
    if ! command -v docker &> /dev/null; then
        ERRORS+=("docker is not installed")
        return
    fi

    local version_output
    if ! version_output=$(docker --version 2>&1); then
        ERRORS+=("Failed to execute 'docker --version'. Docker may not be functioning properly")
        return
    fi

    local version
    version=$(echo "$version_output" | awk '{print $3}' | sed 's/,$//')
    if [ -z "$version" ]; then
        WARNINGS+=("Unable to determine Docker version")
        return
    fi

    if [ "$(printf '%s\n' "$MIN_DOCKER_VERSION" "$version" | sort -V | head -n1)" != "$MIN_DOCKER_VERSION" ]; then
        ERRORS+=("Docker version $version does not meet the minimum requirement of $MIN_DOCKER_VERSION")
    fi

    if ! docker info >/dev/null 2>&1; then
        ERRORS+=("Docker is installed but the Docker daemon is not accessible. Ensure that Docker is running and that your user has permission to access the Docker daemon (for example, by adding your user to the 'docker' group and then logging out and back in).")
        return
    fi
}

check_resources() {
    local cpu_count
    local total_mem_kb

    if [ -f /proc/cpuinfo ]; then
        cpu_count=$(grep -c "^processor" /proc/cpuinfo)
    elif command -v nproc &> /dev/null; then
        cpu_count=$(nproc)
    else
        WARNINGS+=("Unable to determine CPU count")
        cpu_count=0
    fi

    if [ -f /proc/meminfo ]; then
        total_mem_kb=$(grep "MemTotal" /proc/meminfo | awk '{print $2}')
    else
        WARNINGS+=("Unable to determine available memory")
        total_mem_kb=0
    fi

    if [ "$cpu_count" -gt 0 ] && [ "$cpu_count" -lt "$MIN_RECOMMENDED_CPU_COUNT" ]; then
        WARNINGS+=("CPU count ($cpu_count) is below the recommended minimum of $MIN_RECOMMENDED_CPU_COUNT")
    fi

    local total_mem_mb
    if [ "$total_mem_kb" -gt 0 ]; then
        total_mem_mb=$((total_mem_kb / 1024))
        if [ "$total_mem_kb" -lt "$MIN_RECOMMENDED_RAM_KB" ]; then
            WARNINGS+=("RAM (${total_mem_mb}MB) is below the recommended minimum of $((MIN_RECOMMENDED_RAM_KB / 1024))MB")
        fi
    fi
}

check_disk_space() {
    local install_dir="$1"
    local parent_dir
    parent_dir=$(dirname "$install_dir")

    if [ ! -d "$parent_dir" ]; then
        return
    fi

    local df_output
    if ! df_output=$(df -m "$parent_dir" 2>/dev/null); then
        WARNINGS+=("Unable to determine available disk space for $parent_dir")
        return
    fi

    local space_mb
    space_mb=$(echo "$df_output" | awk 'NR==2 {print $4}')
    if [ -z "$space_mb" ]; then
        WARNINGS+=("Unable to determine available disk space for $parent_dir")
        return
    fi

    if [ "$space_mb" -lt "$MIN_RECOMMENDED_DISK_SPACE_MB" ]; then
        WARNINGS+=("Available disk space (${space_mb}MB) is below the recommended minimum of ${MIN_RECOMMENDED_DISK_SPACE_MB}MB")
    fi
}

report_warnings_and_errors() {
    if [ ${#WARNINGS[@]} -gt 0 ] || [ ${#ERRORS[@]} -gt 0 ]; then
        if [ ${#WARNINGS[@]} -gt 0 ]; then
            echo -e "\nWARNINGS:"
            for warning in "${WARNINGS[@]}"; do
                echo "  - $warning"
            done
        fi
        if [ ${#ERRORS[@]} -gt 0 ]; then
            echo -e "\nERRORS:"
            for error in "${ERRORS[@]}"; do
                echo "  - $error"
            done
            echo ""
            die "Please resolve the above errors before proceeding."
        fi
        echo ""
    fi
}

# ── Version resolution ───────────────────────────────────────────────────────

get_latest_version() {
    local tags_json
    tags_json=$(curl -s -f "$REGISTRY_TAGS_URL") || die "Unable to query image registry for latest version"

    local version
    version=$(echo "$tags_json" \
        | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+"' \
        | tr -d '"' \
        | sort -V \
        | tail -1 \
        || true)
    [ -n "$version" ] || die "Unable to determine latest proxy agent version"
    echo "$version"
}

# ── Main ─────────────────────────────────────────────────────────────────────

main() {
    local config_path=""
    local install_dir="$DEFAULT_INSTALL_DIR"

    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                usage
                ;;
            --install-dir)
                [ $# -ge 2 ] || die "--install-dir requires a value"
                install_dir="$2"
                shift 2
                ;;
            -*)
                die "Unknown option: $1"
                ;;
            *)
                [ -z "$config_path" ] || die "Unexpected argument: $1"
                config_path="$1"
                shift
                ;;
        esac
    done

    if [ -z "${config_path:-}" ] && [ -z "${TOKEN:-}" ]; then
        usage
    fi
    if [ -n "${config_path:-}" ] && [ ! -r "$config_path" ]; then
        die "Config file not found or not readable: $config_path"
    fi

    echo -e "Installing Fivetran Proxy Agent...\n"

    # Pre-flight checks
    echo -n "Checking prerequisites... "
    check_dependencies
    check_docker_version
    check_resources
    check_disk_space "$install_dir"

    if [ ${#WARNINGS[@]} -eq 0 ] && [ ${#ERRORS[@]} -eq 0 ]; then
        echo -e "OK\n"
    else
        echo ""
        report_warnings_and_errors
    fi

    # Directory setup
    if [ -d "$install_dir" ]; then
        echo "$install_dir already exists, will re-use it."
    else
        mkdir -p "$install_dir"
    fi

    if [ ! -w "$install_dir" ]; then
        die "Insufficient permissions to write to $install_dir"
    fi

    mkdir -p "$install_dir/config" "$install_dir/logs"

    # Download management script from public repo
    local tmp_script
    tmp_script=$(mktemp "${install_dir}/${AGENT_SCRIPT}.XXXXXX")
    if ! curl -fSsL --max-time 30 "$AGENT_SCRIPT_URL" -o "$tmp_script"; then
        rm -f "$tmp_script"
        die "Failed to download management script from $AGENT_SCRIPT_URL"
    fi
    chmod u+x "$tmp_script"
    mv "$tmp_script" "$install_dir/$AGENT_SCRIPT"

    # Bootstrap or copy config
    if [ -z "${config_path:-}" ]; then
        local api_url="${FIVETRAN_API_URL:-$DEFAULT_FIVETRAN_API_URL}"
        echo "Fetching agent config from $api_url..."
        local response
        response=$(curl -sS -w "\n%{http_code}" \
            -X POST \
            -H "Authorization: Basic ${TOKEN}" \
            -H "Accept: application/json" \
            "${api_url}/proxy-agent/configure") || die "Failed to connect to configure endpoint"
        # Extract status code (after last newline) and body (before last newline) using bash builtins
        local http_code="${response##*$'\n'}"
        local body="${response%$'\n'*}"
        [ "$http_code" = "200" ] || die "Configure endpoint returned HTTP $http_code"
        # umask 177 ensures the file is created with 600 permissions (no read/write for group/other)
        (umask 177 && printf '%s' "$body" > "$install_dir/config/config.json")
    else
        mv "$config_path" "$install_dir/config/config.json"
        chmod 600 "$install_dir/config/config.json"
        echo "Your config file has been moved to $install_dir/config/config.json"
        echo "Note: Keep the config file if you need to roll back to the previous installation"
    fi

    # Resolve and pin version
    echo "Resolving latest proxy agent version..."
    local version
    version=$(get_latest_version)
    echo "$version" > "$install_dir/version"
    echo "Using version $version"

    # Start agent
    echo "Changing current directory to $install_dir"
    cd "$install_dir"
    if ! ./$AGENT_SCRIPT start; then
        echo "Installation complete, but agent failed to start."
        echo "Please review the agent container logs for more detail."
        # TODO: Print the contents of the latest log file
        echo "To try to start the agent again, run: ./$AGENT_SCRIPT start"
        exit 1
    fi

    echo -e "\nInstallation complete."
    echo "Install directory: $install_dir"
}

main "$@"
