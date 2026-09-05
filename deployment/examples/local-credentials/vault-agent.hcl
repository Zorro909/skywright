# Run as the SkyPilot operating-system identity, beside its API server.
# This token can read only the exact Kubernetes binding path, plus default
# token-self lookup/renewal permissions required by Agent.
exit_after_auth = true
vault { address = "http://127.0.0.1:8200" }
auto_auth {
  method "token_file" {
    config = { token_file_path = "/run/skywright/skypilot-vault-token" }
  }
}
template {
  source = "/etc/skywright/kubeconfig.ctmpl"
  destination = "/run/skywright/kubeconfig"
  perms = "0400"
  error_on_missing_key = true
}
