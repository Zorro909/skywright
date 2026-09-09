"""Exercise the installed controller's error path after target Pod loss."""
import asyncio
from unittest.mock import Mock, patch

from sky import backends, exceptions
from sky.jobs import utils

handle = Mock(spec=backends.CloudVmRayResourceHandle)
backend = Mock(spec=backends.CloudVmRayBackend)
backend.get_job_status.side_effect = exceptions.CommandError(
    1, "qualification-status", "status command failed", "target Pod no longer exists"
)
with patch.object(utils.global_user_state, "get_handle_from_cluster_name", return_value=handle), \
        patch.object(utils.managed_job_runtime, "is_registered", return_value=False):
    status, reason = asyncio.run(utils.get_job_status(backend, "qualification-cluster", 1))
assert status is None and "Returncode: 1" in reason
backend.get_job_status.assert_called_once_with(handle, job_ids=[1], stream_logs=False)
print("Missing target Pod remains a recoverable status observation")
