/* Android-only differential harness: run beside original liblc3.so, isolated
 * libeg3.so and the AAR JNI wrapper. Exercises silence, tones and seeded noise
 * across 256 contiguous predictor frames; any changed sample fails. */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <math.h>
#define S(handle,fun,name) __typeof__(fun)* name=dlsym(handle,#fun);if(!name){puts(dlerror());return 1;}
extern unsigned lc3_decoder_size(int,int),lc3_encoder_size(int,int);
extern void *lc3_setup_decoder(int,int,int,void*), *lc3_setup_encoder(int,int,int,void*);
extern int lc3_decode(void*,const void*,int,int,void*,int),lc3_encode(void*,int,const void*,int,int,void*);
extern void *even_lc3_create(void);extern int even_lc3_decode(void*,const uint8_t*,size_t,uint8_t*);
int main(){
 void *old=dlopen("./liblc3.so",RTLD_NOW|RTLD_LOCAL), *sdk=dlopen("./libeven_lc3.so",RTLD_NOW|RTLD_LOCAL);
 if(!old||!sdk){puts(dlerror());return 1;}
 S(old,lc3_decoder_size,ds);S(old,lc3_setup_decoder,dc);S(old,lc3_decode,decode);
 S(old,lc3_encoder_size,es);S(old,lc3_setup_encoder,ec);S(old,lc3_encode,encode);
 S(sdk,even_lc3_create,cc);S(sdk,even_lc3_decode,cd);
 void *d=dc(10000,16000,0,calloc(1,ds(10000,16000))), *e=ec(10000,16000,0,calloc(1,es(10000,16000))),*candidate=cc();
 uint8_t encoded[40];int16_t samples[160],q[160],z[160];unsigned seed=1;int max=0,above=0,changed=0;
 for(int f=0;f<256;f++){
   for(int i=0;i<160;i++){int n=f*160+i;seed=1664525u*seed+1013904223u;
    samples[i]=(int16_t)(f<20?0:(f<120?12000.0*sin(n*.07)+6000.0*sin(n*.11):(((int)(seed>>16))-32768)*.7));}
   if(encode(e,0,samples,1,40,encoded)||decode(d,encoded,40,0,q,1)||cd(candidate,encoded,40,(uint8_t*)z))return 2;
   for(int i=0;i<160;i++){int diff=abs(q[i]-z[i]);if(diff>max)max=diff;if(diff>1)above++;if(diff)changed++;}
 }
 printf("old Android codec vs AAR: 40960 samples, %d changed, %d above 1 LSB, max %d LSB\n",changed,above,max);
 return changed ? 3 : 0;
}
