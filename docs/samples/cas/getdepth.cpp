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
   int depth= svr.getMsgCount("MSGMBFE_1");
   if(depth==-1)
   {
       printf("Get queue depth error:%s\n",svr.getErrMsg());
       return -1;
       
   } 
   printf("Queue depth:%d\n",depth);
   svr.disconnectSvr();

   printf("disconnect\n");

   
   return 0; 
}
