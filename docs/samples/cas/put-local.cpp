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
   const char* msg ="Hello CNCC";
   svr.tranBegin();
   printf("tranBegin\n");
   
   //ghguo  add
//   sleep(30);
   CNCCMQ_MSGOPTIONS* msgOpts=(CNCCMQ_MSGOPTIONS*)malloc(sizeof(CNCCMQ_MSGOPTIONS));
   CNCCMQ_PUTOPTIONS *putOpts=(CNCCMQ_PUTOPTIONS *)malloc(sizeof(CNCCMQ_PUTOPTIONS));
   memset(msgOpts,0,sizeof(CNCCMQ_MSGOPTIONS));
   memset(putOpts,0,sizeof(CNCCMQ_PUTOPTIONS));
   memcpy(msgOpts->correlationId,"12345623",8);
   msgOpts->msgSize=strlen(msg);
   msgOpts->persistant=true;
   
   putOpts->transacted=true;
   queue.put(msg,msgOpts,putOpts);
   printf("put\n");
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
