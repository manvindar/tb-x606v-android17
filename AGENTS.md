# Lenovo Android release policy

For every new Android release adopted by this Lenovo TB-X606V port, build both editions:

- AOSP / non-Pixel: without the Pixel Tablet UI, launcher or app layer. Produce images with and without Google apps when supported by the build.
- Pixel: with the matching Pixel Tablet layer for that Android build.

Do not treat a Pixel-only build as completion of a new Android release update. Keep both editions on the same Android base, record exact source and input identities, and state build and device validation separately for each edition. Passing checks or booting one edition does not validate the other. This build policy does not authorize flashing a tablet or claiming an untested image is stable.

Owner instruction: November 16, 2026.
