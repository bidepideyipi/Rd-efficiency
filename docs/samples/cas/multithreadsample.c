#include <pthread.h>
#include "../../msgsvr/interface/cas/cas-clt.h"

#define THREAD_NUM 10


typedef struct JOB {
    cnccSvr* svr;
    cnccQueue* queue;
}JOB;

void* put_thread(void* task) {
JOB* job=(JOB*)task;
int i = 0;
    for(i = 0; i < 100; i++) {
        tranBegin(job->svr, NULL);

        CNCCMQ_MSGOPTIONS *msgOpts = (CNCCMQ_MSGOPTIONS *)malloc(sizeof(CNCCMQ_MSGOPTIONS));
        CNCCMQ_PUTOPTIONS *putOpts = (CNCCMQ_PUTOPTIONS *)malloc(sizeof(CNCCMQ_PUTOPTIONS));
        memset(msgOpts,0,sizeof(CNCCMQ_MSGOPTIONS));
        memset(putOpts,0,sizeof(CNCCMQ_PUTOPTIONS));
        const char* msg ="Hello CNCC";
        msgOpts->msgSize=strlen(msg);
        msgOpts->persistant=true;
        putOpts->transacted=0;

        bool ret = put(job->queue, msg,msgOpts,putOpts);
        if (ret == false) {
            printf("put失败!\n");
            printf("errcode:%d,errmsg:%s\n", job->queue->errcode, errstr(job->queue->errcode));
            continue;
        }

        commit(job->svr, NULL);
        sleep(1);
}
disconnectSvr(job->svr, NULL);
return NULL;
}
    
JOB * job[THREAD_NUM];
int main(int argc,char **argv) {
pthread_t tid;
int i = 0;
    for(i=0;i<THREAD_NUM;i++) {
        job[i]=(JOB*)malloc(sizeof(JOB));
        job[i]->svr= malloc(sizeof(cnccSvr));
        memset(job[i]->svr, 0 , sizeof(cnccSvr));
        cnccSvrInit(job[i]->svr);
        connectSvr(job[i]->svr, "testA", NULL);
                     
        job[i]->queue= malloc(sizeof(cnccQueue));
        cnccQueueInit(job[i]->svr, job[i]->queue);
        openQueue(job[i]->queue, "testA_B",0, NULL,NULL );
        pthread_create(&tid,NULL,put_thread,(void*)job[i]);
        pthread_detach(tid);
    }
    sleep(120);
    return 0;
}