#include <pthread.h>
#include "cas-clt.hpp"
#define THREAD_NUM 10
typedef struct JOB
{
    cnccSvr* svr;
    cnccQueue* queue;
}JOB;
static int msgnum=0;
void* get_thread(void* task)
{
   JOB* job=(JOB*)task;
   char *msg=(char*)malloc(20*1024*1024);
   char msgid[32];
   long msgSize=20*1024*1024;
   while(1)
   {
       job->svr->tranBegin();
       printf("tranBegin\n");

       if(job->svr->getMsgCount(job->queue->getName())==0)
       {
           printf("The queue is empty\n");
           sleep(1);
           continue;
       }
       memset(msg,0,msgSize);
       CNCCMQ_MSGOPTIONS* msgOpts=(CNCCMQ_MSGOPTIONS*)malloc(sizeof(CNCCMQ_MSGOPTIONS));
       CNCCMQ_GETOPTIONS *getOpts=(CNCCMQ_GETOPTIONS *)malloc(sizeof(CNCCMQ_GETOPTIONS));
       memset(msgOpts,0,sizeof(CNCCMQ_MSGOPTIONS));
       memset(getOpts,0,sizeof(CNCCMQ_GETOPTIONS));
       memcpy(msgOpts->correlationId,"12345678",8);
       msgOpts->msgSize=strlen(msg);
       msgOpts->persistant=true;
       getOpts->transacted=true;
       if(job->queue->get(msg,msgSize,msgOpts,getOpts)==-1)
       {
           printf("Get MSG error:%s\n",job->queue->getErrMsg());
       }
       else
       {
          printf("Msg Get: id%s size:%ld Content:%s\n",msgOpts->msgId,msgOpts->msgSize,msg);
       }
       printf("get\n");
       msgnum++;
       job->svr->commit();
       printf("tran Commit\n");
       if(msgnum%1000==0)
           printf("Get Msg Number:%d\n",msgnum);
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
       pthread_create(&tid,NULL,get_thread,(void*)job[i]);
       pthread_detach(tid);
   }
   sleep(1000);
   return 0; 
}

