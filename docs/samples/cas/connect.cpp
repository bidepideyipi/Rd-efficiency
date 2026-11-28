#include "cas-clt.hpp"

int main(int argc,char **argv)
{
   cnccSvr svr;
   if(svr.connectSvr("QMUMBFEA")==false) 
   {
       printf("Connect the qmgr  errcode:%d,error:%s\n",svr.getErrCode(),svr.getErrMsg());
       return -1;
   }
   printf("connect the svr\n");
   return 0; 
}
