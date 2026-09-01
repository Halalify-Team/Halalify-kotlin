from pathlib import Path
import struct
b=Path(r'C:\Users\awad\Downloads\Miku\persona\ctf\Giyu_check\Giyu.exe').read_bytes(); base=0x140000000
names={0x3000:'GetCurrentThreadId',0x3008:'RtlLookupFunctionEntry',0x3010:'RtlVirtualUnwind',0x3018:'UnhandledExceptionFilter',0x3020:'SetUnhandledExceptionFilter',0x3028:'GetModuleHandleW',0x3030:'IsDebuggerPresent',0x3038:'InitializeSListHead',0x3040:'GetSystemTimeAsFileTime',0x3048:'RtlCaptureContext',0x3050:'GetCurrentProcessId',0x3058:'QueryPerformanceCounter',0x3060:'IsProcessorFeaturePresent',0x3068:'TerminateProcess',0x3070:'GetCurrentProcess',0x3080:'__current_exception',0x3088:'__current_exception_context',0x3090:'memset',0x30d8:'_initialize_onexit_table',0x30e0:'_register_onexit_function',0x30e8:'_register_thread_local_exe_atexit_callback',0x30f0:'terminate',0x30f8:'_seh_filter_exe',0x3100:'_set_app_type',0x3108:'__p___argv',0x3110:'_c_exit',0x3118:'_cexit',0x3120:'__p___argc',0x3128:'_crt_atexit',0x3130:'_exit',0x3138:'exit',0x3140:'_initterm_e',0x3148:'_initterm',0x3150:'_get_initial_narrow_environment',0x3158:'_initialize_narrow_environment',0x3160:'_configure_narrow_argv',0x3170:'__stdio_common_vfscanf',0x3178:'__stdio_common_vfprintf',0x3180:'__acrt_iob_func',0x3188:'__p__commode',0x3190:'_set_fmode'}
for o in range(0x1000,len(b)-6):
 if b[o:o+2] not in (b'\xff\x15',b'\xff\x25'): continue
 d=struct.unpack_from('<i',b,o+2)[0]; t=o+6+d
 if t in names: print(f'{o:05x}: {"call" if b[o+1]==0x15 else "jmp"} [{t:04x}] {names[t]}')
