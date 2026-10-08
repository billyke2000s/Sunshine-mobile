#include <jni.h>
#include <enet/enet.h>
#include <opus.h>
#include "rs.h"
#include <vector>
#include <memory>
#include <cstring>
#include <mutex>

struct Control { ENetHost* host; ENetPeer* peer=nullptr; ENetAddress expected; uint32_t data; };
static std::once_flag init;
static jbyteArray bytes(JNIEnv* env,const uint8_t* p,size_t n) {
    auto b=env->NewByteArray(n); if(b) env->SetByteArrayRegion(b,0,n,reinterpret_cast<const jbyte*>(p)); return b;
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_sunshinemobile_protocol_Native_controlCreate(JNIEnv* e,jclass,jstring ip,jint port,jint data) {
    std::call_once(init,[]{enet_initialize(); reed_solomon_init();});
    auto c=std::make_unique<Control>(); ENetAddress bind{};
    if(enet_address_set_host(&bind,"0.0.0.0")!=0) return 0;
    enet_address_set_port(&bind,port);
    const char* s=e->GetStringUTFChars(ip,nullptr); int r=enet_address_set_host(&c->expected,s); e->ReleaseStringUTFChars(ip,s); if(r!=0) return 0;
    c->data=data; c->host=enet_host_create(AF_INET,&bind,1,16,0,0); if(!c->host) return 0;
    return reinterpret_cast<jlong>(c.release());
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_sunshinemobile_protocol_Native_controlPoll(JNIEnv* e,jclass,jlong h,jint ms) {
    auto c=reinterpret_cast<Control*>(h); ENetEvent event{};
    if(enet_host_service(c->host,&event,ms)<=0) return nullptr;
    if(event.type==ENET_EVENT_TYPE_CONNECT) {
        ENetAddress incoming=event.peer->address; enet_address_set_port(&incoming,0);
        if(!enet_address_equal(&incoming,&c->expected) || event.data!=c->data || c->peer) { enet_peer_reset(event.peer); return nullptr; }
        c->peer=event.peer; enet_peer_timeout(c->peer,0,5000,10000); uint8_t b=1; return bytes(e,&b,1);
    }
    if(event.type==ENET_EVENT_TYPE_DISCONNECT) { if(event.peer==c->peer) { c->peer=nullptr; uint8_t b=2; return bytes(e,&b,1); } }
    if(event.type==ENET_EVENT_TYPE_RECEIVE) {
        jbyteArray out=nullptr;
        if(event.peer==c->peer && event.packet->dataLength<=8192) { std::vector<uint8_t> b(event.packet->dataLength+1); b[0]=3; std::memcpy(b.data()+1,event.packet->data,event.packet->dataLength); out=bytes(e,b.data(),b.size()); }
        enet_packet_destroy(event.packet); return out;
    }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_dev_sunshinemobile_protocol_Native_controlDestroy(JNIEnv*,jclass,jlong h) { auto c=reinterpret_cast<Control*>(h); if(c) { enet_host_destroy(c->host); delete c; } }
extern "C" JNIEXPORT jlong JNICALL Java_dev_sunshinemobile_protocol_Native_opusCreate(JNIEnv*,jclass,jint samples) {
    int err=0; OpusEncoder* enc=opus_encoder_create(48000,2,OPUS_APPLICATION_RESTRICTED_LOWDELAY,&err); if(err!=OPUS_OK) return 0;
    opus_encoder_ctl(enc,OPUS_SET_BITRATE(96000)); opus_encoder_ctl(enc,OPUS_SET_VBR(0)); opus_encoder_ctl(enc,OPUS_SET_COMPLEXITY(5)); return reinterpret_cast<jlong>(enc);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_sunshinemobile_protocol_Native_opusEncode(JNIEnv* e,jclass,jlong h,jshortArray pcm,jint samples) {
    if(!h || e->GetArrayLength(pcm)<samples*2 || (samples!=240 && samples!=480 && samples!=960)) return nullptr;
    auto p=e->GetShortArrayElements(pcm,nullptr); unsigned char out[1276]; int n=opus_encode(reinterpret_cast<OpusEncoder*>(h),p,samples,out,sizeof(out)); e->ReleaseShortArrayElements(pcm,p,JNI_ABORT); return n<0?nullptr:bytes(e,out,n);
}
extern "C" JNIEXPORT void JNICALL Java_dev_sunshinemobile_protocol_Native_opusDestroy(JNIEnv*,jclass,jlong h) { if(h) opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(h)); }
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_sunshinemobile_protocol_Native_parity(JNIEnv* e,jclass,jobjectArray arrays,jint count,jboolean audio) {
    std::call_once(init,[]{enet_initialize(); reed_solomon_init();});
    int ds=e->GetArrayLength(arrays); if(ds<1 || count<1 || ds+count>255) return nullptr;
    auto first=static_cast<jbyteArray>(e->GetObjectArrayElement(arrays,0)); int len=e->GetArrayLength(first); e->DeleteLocalRef(first); if(len<1||len>16384) return nullptr;
    int padded=reed_solomon_padded_size(len); std::vector<uint8_t*> shards(ds+count);
    for(auto& p:shards) { p=static_cast<uint8_t*>(reed_solomon_aligned_alloc(padded)); if(!p) { for(auto q:shards) if(q) reed_solomon_free(q); return nullptr; } std::memset(p,0,padded); }
    bool valid=true;
    for(int i=0;i<ds;i++) { auto b=static_cast<jbyteArray>(e->GetObjectArrayElement(arrays,i)); if(e->GetArrayLength(b)!=len) valid=false; else e->GetByteArrayRegion(b,0,len,reinterpret_cast<jbyte*>(shards[i])); e->DeleteLocalRef(b); }
    auto rs=reed_solomon_new(ds,count);
    if(audio && ds==4 && count==2) { uint8_t matrix[]={0x77,0x40,0x38,0x0e,0xc7,0xa7,0x0d,0x6c}; std::memcpy(rs->p,matrix,8); }
    if(valid) reed_solomon_encode(rs,shards.data(),ds+count,padded);
    auto cls=e->FindClass("[B"); auto result=valid?e->NewObjectArray(count,cls,nullptr):nullptr;
    if(result) for(int i=0;i<count;i++) { auto b=bytes(e,shards[ds+i],len); e->SetObjectArrayElement(result,i,b); e->DeleteLocalRef(b); }
    reed_solomon_release(rs); for(auto p:shards) reed_solomon_free(p); return result;
}
