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
   
   //ghguo  add
//   sleep(30);
   CNCCMQ_PUTOPTIONS *putopt=(CNCCMQ_PUTOPTIONS *)malloc(sizeof(CNCCMQ_PUTOPTIONS));
   memset(putopt,0,sizeof(CNCCMQ_PUTOPTIONS));
   putopt->transacted=0;
   queue.put(msg,strlen(msg),NULL,0,NULL,putopt);
   printf("put\n");
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
