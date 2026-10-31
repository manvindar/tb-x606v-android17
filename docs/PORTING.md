# Moving the patches to a newer GSI build

The builder patches binaries and dex code of Google's GSI. Every patch has to find its target again in a new build. Order of work:

1. `python build/check_patches.py system.img` (or the workflow with `mode: check`, about two minutes) runs every patch tool against the image and lists the ones that no longer apply, all at once.
2. Patch tools that search by pattern or symbol (`patch_libc.py`, `patch_libui.py`, `patch_pgsetup.py`, `patch_libmeminfo.py`, `patch_libbinder.py`, `patch_libandroid_runtime.py`, `patch_services_jar.py`,
   `patch_service_connectivity_jar.py`, the init check in `build_image17.py`) normally survive a new build. The dex tools resolve method, field and string indices by name, because those indices change with every build.
3. Tools with plain addresses (`tools/bpf/patch_binaries.py`, `tools/bpf/patch_bluetooth.py`) have one table per build, chosen by the SHA-256 of the input. For a new build, find each site with
   `python tools/porting/find_site.py OLD_BINARY NEW_BINARY 0xADDR ...` (instruction windows with masked immediates; newer builds add `bti c` / `paciasp`, and some `cmp w,#1 ; b.eq` became `cbz`),
   check the result against the disassembly, and add a table entry. Sites the window search misses (the compiler changed the surrounding code) are found by reading both disassemblies side by side: the control flow is the same.
4. A tool that passes on the new build must give byte-identical output on the old one (compare with the previous version of the tool), so a main-branch build does not change.
5. Build (`build/build_image17.py`), check with `e2fsck -fn`, and expect surprises at boot: the checks only prove that the patch sites were found, not that the new code behaves like the old.

DP11.261016.003 notes: the code is built with PAC and BTI (`paciasp`, `autiasp`, `bti c`: no-ops on this CPU and kernel), `HintManagerService.getSupportInfo` now calls `getInterfaceVersion()` on the power HAL first,
and the tethering APEX binaries (`netbpfload`, `libnetd_updatable.so`, `libservice-connectivity.so`) were recompiled with the same logic at different addresses.

## Debugging a boot that restarts (what worked on DP11.261016.003)
- `init` can be wrapped to see why it dies early (`tools/debug/initwrap.c`: runs the real init under ptrace and replays its log lines, which the kernel otherwise rate-limits); `tools/debug/trcx.c` runs one command under ptrace and keeps the last syscalls.
- The panic "Attempted to kill init" after about 30 s was init rebooting on purpose: the `bpfloader` service has `reboot_on_failure`. The tracer that wraps `netbpfload` writes its log to `/dev/bpftrace.log` (copied to /cache/bootdiag by the boot diagnostics service) because the loader's `/dev/kmsg` writes are rate-limited (310 lines became 14).
- Cause there: the new netd.o counters use the BPF atomic fetch-add form, which kernel 4.9 rejects ("BPF_STX uses reserved fields"); `patch_netd_o.py` rewrites them to XADD.
- Then `cpustatsd` aborted every 5 s because init cannot raise the ambient capabilities BPF (39) and PERFMON (38) on kernel 4.9; the builder strips them from its rc.
