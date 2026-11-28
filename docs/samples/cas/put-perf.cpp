#include <pthread.h>
#include "cas-clt.hpp"
#define THREAD_NUM 10
typedef struct JOB
{
    cnccSvr* svr;
    cnccQueue* queue;
}JOB;
static int msgnum=0;
void* put_thread(void* task)
{
   JOB* job=(JOB*)task;
   while(1)
   {
       job->svr->tranBegin();
       printf("tranBegin\n");
       const char* msg ="Hello CNCC";
       CNCCMQ_MSGOPTIONS* msgOpts=(CNCCMQ_MSGOPTIONS*)malloc(sizeof(CNCCMQ_MSGOPTIONS));
       CNCCMQ_PUTOPTIONS *putopt=(CNCCMQ_PUTOPTIONS *)malloc(sizeof(CNCCMQ_PUTOPTIONS));
       memset(msgOpts,0,sizeof(CNCCMQ_MSGOPTIONS));
       memset(putopt,0,sizeof(CNCCMQ_PUTOPTIONS));
       memcpy(msgOpts->correlationId,"12345678",8);
       msgOpts->msgSize=strlen(msg);
       msgOpts->persistant=true;
       putopt->transacted=1;
       job->queue->put(msg,msgOpts,putopt);
       printf("put\n");
       msgnum++;
       job->svr->commit();
       printf("tran Commit\n");
       if(msgnum%1000==0)
           printf("Put Msg Number:%d\n",msgnum);
   } 
} 
JOB * job[THREAD_NUM];
int main(int argc,char **argv)
{
   cnccSvr* svr[THREAD_NUM];
   cnccQueue* queue[THREAD_NUM];
   pthread_t tid;
   for(int i=0;i<THREAD_NUM;i++)
   {
       job[i]=(JOB*)malloc(sizeof(JOB));
       job[i]->svr= new cnccSvr();
       if(job[i]->svr->connectSvr("QMUMBFEA")==false) 
       {
           printf("Connect the qmgr errcode:%d,error:%s\n",job[i]->svr->getErrCode(),job[i]->svr->getErrMsg());
           return -1;
       }
       printf("connect the svr\n");
       job[i]->queue= new cnccQueue(job[i]->svr);
       if(job[i]->queue->open("MSGMBFE_1",0)==false)
       {
           printf("open queue error:%s\n",job[i]->queue->getErrMsg());
           return -1;
       }
       printf("Open the queue\n");
       pthread_create(&tid,NULL,put_thread,(void*)job[i]);
       pthread_detach(tid);
   }
   sleep(1000);
   return 0; 
}

