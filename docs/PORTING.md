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
