# v0.4 Internet relay deployment status

Branch: `v0.4`

The Android clients already contain `RelaySocket` support and end-to-end encrypted session transport. Internet Remote ID routing becomes active after the Cloudflare Worker/Durable Object relay is deployed and its HTTPS URL is written into `v0.3-overrides/RelayConfig.java`.

Status: relay deployment pending.

Next steps:
1. Deploy `relay/cloudflare`.
2. Verify `/health` returns `RemotePhone relay OK`.
3. Put the deployed HTTPS URL into `RelayConfig.BASE_URL`.
4. Build and install v0.4 on Host and Controller.
5. Test with Host on Wi-Fi and Controller on mobile data / another network.
