#!/usr/bin/env python3
"""Compare patched streaming DSP output to the pinned upstream implementation."""
import ctypes as c
from pathlib import Path
import sys
root = Path(__file__).resolve().parent.parent
class Dpdf(c.Structure): _fields_ = [('model',c.c_char_p),('limit',c.c_float)]
class Config(c.Structure): _fields_ = [('gtcrn',c.c_char_p),('threads',c.c_int),('debug',c.c_int),('provider',c.c_char_p),('dpdf',Dpdf)]
class Audio(c.Structure): _fields_ = [('samples',c.POINTER(c.c_float)),('n',c.c_int),('rate',c.c_int)]
c.CDLL(str(root/'native/vendor/macos/lib/libonnxruntime.dylib'),mode=c.RTLD_GLOBAL)
def process(library,model):
    lib=c.CDLL(library)
    create=lib.SherpaOnnxCreateOnlineSpeechDenoiser;create.argtypes=[c.POINTER(Config)];create.restype=c.c_void_p
    run=lib.SherpaOnnxOnlineSpeechDenoiserRun;run.argtypes=[c.c_void_p,c.POINTER(c.c_float),c.c_int,c.c_int];run.restype=c.POINTER(Audio)
    destroy=lib.SherpaOnnxDestroyOnlineSpeechDenoiser;destroy.argtypes=[c.c_void_p]
    free=lib.SherpaOnnxDestroyDenoisedAudio;free.argtypes=[c.POINTER(Audio)]
    ptr=create(c.byref(Config(b'',1,0,b'cpu',Dpdf(str(model).encode(),0))))
    assert ptr
    pcm=(root/'native/tests/fixtures/speech.pcm').read_bytes();samples=[int.from_bytes(pcm[i:i+2],'little',signed=True)/32768.0 for i in range(0,len(pcm),2)]
    output=[]
    try:
        for start in range(0,len(samples)-960,960):
            block=(c.c_float*960)(*samples[start:start+960]);r=run(ptr,block,960,48000)
            if r:
                assert r.contents.rate==48000
                output.extend(r.contents.samples[:r.contents.n]);free(r)
    finally: destroy(ptr)
    return output
for model in (root/'native/models').glob('dpdf*.onnx'):
    reference=process(sys.argv[1],model);actual=process(sys.argv[2],model)
    assert len(reference)==len(actual)
    error=max(abs(a-b) for a,b in zip(reference,actual))
    assert error<0.0002,(model.name,error)
    print(model.name,'samples',len(actual),'maximum numeric difference',error)
