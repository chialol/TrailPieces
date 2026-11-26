# Backend

There is no application server. Packages are static files in a private S3 bucket, delivered over CloudFront. The publishing tool and the on-device loader are documented in [tools/publish/README.md](../tools/publish/README.md).

Accounts, purchases, and signed URLs are not part of this step. A future access policy can be added beside `public` without treating an unknown policy as granted.
