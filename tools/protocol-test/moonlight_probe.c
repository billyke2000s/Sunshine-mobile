#include "Limelight.h"
#include <opus/opus_multistream.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
#include <stdatomic.h>

static atomic_int frames=0,audio=0,ended=0;
static FILE* output;
static OpusMSDecoder* decoder;
static int setup_video(int format,int width,int height,int fps,void* ctx,int flags) {
    if(format!=VIDEO_FORMAT_H264 || width!=1280 || height!=720 || fps!=30) return -1; return 0;
}
static int submit(PDECODE_UNIT unit) {
    for(PLENTRY p=unit->bufferList;p;p=p->next) fwrite(p->data,1,p->length,output);
    atomic_fetch_add(&frames,1); return DR_OK;
}
static int init_audio(int config,const POPUS_MULTISTREAM_CONFIGURATION opus,void* context,int flags) {
    int err; decoder=opus_multistream_decoder_create(opus->sampleRate,opus->channelCount,opus->streams,opus->coupledStreams,opus->mapping,&err);
    return err==OPUS_OK?0:-1;
}
static void decode(char* data,int len) { short pcm[5760*2]; int n=opus_multistream_decode(decoder,(unsigned char*)data,len,pcm,5760,0); if(n>0) atomic_fetch_add(&audio,1); }
static void cleanup_audio(void) { if(decoder) opus_multistream_decoder_destroy(decoder); decoder=NULL; }
static void stage_failed(int stage,int code) { fprintf(stderr,"FAILED stage %s: %d\n",LiGetStageName(stage),code); atomic_store(&ended,1); }
static void terminated(int code) { fprintf(stderr,"TERMINATED %d\n",code); atomic_store(&ended,1); }
static void log_message(const char* fmt,...) { va_list args; va_start(args,fmt); vfprintf(stderr,fmt,args); va_end(args); }
int main(int argc,char**argv) {
    output=fopen(argv[1],"wb"); if(!output) return 2;
    SERVER_INFORMATION si; STREAM_CONFIGURATION sc; DECODER_RENDERER_CALLBACKS vr; AUDIO_RENDERER_CALLBACKS ar; CONNECTION_LISTENER_CALLBACKS cl;
    LiInitializeServerInformation(&si); LiInitializeStreamConfiguration(&sc); LiInitializeVideoCallbacks(&vr); LiInitializeAudioCallbacks(&ar); LiInitializeConnectionCallbacks(&cl);
    si.address="127.0.0.1"; si.serverInfoAppVersion="7.1.450.-1"; si.serverInfoGfeVersion="3.23.0.74"; si.rtspSessionUrl="rtspenc://127.0.0.1:48010"; si.serverCodecModeSupport=1;
    sc.width=1280; sc.height=720; sc.fps=30; sc.bitrate=10000; sc.packetSize=1024; sc.streamingRemotely=STREAM_CFG_LOCAL; sc.audioConfiguration=AUDIO_CONFIGURATION_STEREO; sc.supportedVideoFormats=VIDEO_FORMAT_H264; sc.encryptionFlags=argc>2&&strcmp(argv[2],"2")==0?ENCFLG_NONE:ENCFLG_ALL;
    unsigned char key[]={0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff}; memcpy(sc.remoteInputAesKey,key,16); sc.remoteInputAesIv[0]=0; sc.remoteInputAesIv[1]=0; sc.remoteInputAesIv[2]=0x30; sc.remoteInputAesIv[3]=0x39;
    vr.setup=setup_video; vr.submitDecodeUnit=submit; vr.capabilities=CAPABILITY_DIRECT_SUBMIT;
    ar.init=init_audio; ar.decodeAndPlaySample=decode; ar.cleanup=cleanup_audio; ar.capabilities=CAPABILITY_DIRECT_SUBMIT;
    cl.stageFailed=stage_failed; cl.connectionTerminated=terminated; cl.logMessage=log_message;
    RTP_AUDIO_STATS audioStats={0}; RTP_VIDEO_STATS videoStats={0};
    int result=LiStartConnection(&si,&sc,&cl,&vr,&ar,NULL,0,NULL,0);
    if(result==0) { for(int i=0;i<120&&!atomic_load(&ended);i++) usleep(100000); audioStats=*LiGetRTPAudioStats(); videoStats=*LiGetRTPVideoStats(); LiStopConnection(); }
    fclose(output);
    printf("Moonlight upstream: start=%d video_frames=%d opus_packets=%d\n",result,atomic_load(&frames),atomic_load(&audio));
    printf("FEC: video_data=%u video_parity=%u audio_parity=%u audio_recovered=%u invalid_audio=%u invalid_audio_fec=%u\n",videoStats.packetCountVideo,videoStats.packetCountFec,audioStats.packetCountFec,audioStats.packetCountFecRecovered,audioStats.packetCountInvalid,audioStats.packetCountFecInvalid);
    if(argc>2 && strcmp(argv[2],"3")==0 && audioStats.packetCountFecRecovered==0) return 1;
    if(videoStats.packetCountFec==0 || audioStats.packetCountFec==0 || audioStats.packetCountInvalid || audioStats.packetCountFecInvalid) return 1;
    return result==0 && atomic_load(&frames)>=100 && atomic_load(&audio)>=300 ? 0:1;
}
