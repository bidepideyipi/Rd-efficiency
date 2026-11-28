#include "cas-clt.hpp"
int main(int argc,char **argv)
{
   cnccSvr svr;
   if(svr.connectSvr("QMUMBFEA")==false) 
   {
       printf("Connect the qmgr errcode:%d,error:%s\n",svr.getErrCode(),svr.getErrMsg());
       return -1;
   }
   printf("connect the svr\n");
   cnccQueue queue(&svr);
   if(queue.open("MSGMBFE_1",0)==false)
   {
       printf("open queue error:%s\n",queue.getErrMsg());
       return -1;
   }
   printf("Open the queue\n");
   svr.tranBegin();
   printf("tranBegin\n");
   
   char *msg=(char*)malloc(20*1024*1024);
   char msgid[32];
   long msgSize=20*1024*1024;
   CNCCMQ_MSGOPTIONS* msgOpts=(CNCCMQ_MSGOPTIONS*)malloc(sizeof(CNCCMQ_MSGOPTIONS));
   CNCCMQ_GETOPTIONS *getOpts=(CNCCMQ_GETOPTIONS *)malloc(sizeof(CNCCMQ_GETOPTIONS));
   memset(msgOpts,0,sizeof(CNCCMQ_MSGOPTIONS));
   memset(getOpts,0,sizeof(CNCCMQ_GETOPTIONS));
//   strncpy(msgOpts->correlationId,"12345623",8);
   msgOpts->msgSize=strnlen(msg, msgSize);
   msgOpts->persistant=true;
   getOpts->transacted=true;
   if(queue.get(msg,msgSize,msgOpts,getOpts)==-1)
   {
       printf("Get MSG error:%s\n",queue.getErrMsg());
   }
   else
   {
      printf("Msg Get: id%s size:%ld Content:%s\n",msgOpts->msgId,msgOpts->msgSize,msg);
   }
   printf("get\n");
   svr.commit();
   printf("commit\n");
   if(queue.close()==false)
   {
       printf("open queue error:%s\n",queue.getErrMsg());
       return -1;
   }
   printf("close\n");
   svr.disconnectSvr();
   printf("disconnect\n");

   return 0; 
}
