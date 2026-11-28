#include "cas-clt.hpp"
#include <signal.h>
#include <sys/time.h>


int count=0;

void printtime(const char *str)
{
    struct timeval tv;
    gettimeofday(&tv,NULL);
    printf("[%s]:  %ld:%ld\n",str,tv.tv_sec,tv.tv_usec);
}

int main(int argc,char **argv)
{
   signal(SIGPIPE,SIG_IGN);
   cnccSvr svr;
   if(argc!=3)
   {
       printf("Parameter error, number:%d\n",argc);
       printf("Usage: get [qmgrName] [queueName]\n");
       return -1;
   }
   
   char *msg=(char*)malloc(20*1024*1024);
   CNCCMQ_MSGOPTIONS* msgOpts=(CNCCMQ_MSGOPTIONS*)malloc(sizeof(CNCCMQ_MSGOPTIONS));
   CNCCMQ_GETOPTIONS *getOpts=(CNCCMQ_GETOPTIONS *)malloc(sizeof(CNCCMQ_GETOPTIONS));

   while(1)
   {
       if(svr.connectSvr(argv[1])==false) 
       {
           printf("Connect the qmgr errcode:%d,error:%s\n",svr.getErrCode(),svr.getErrMsg());
           return -1;
       }
       cnccQueue queue(&svr);
       if(queue.open(argv[2],0)==false)
       {
           printf("open queue error:%s\n",queue.getErrMsg());
           return -1;
       }
	if(svr.getMsgCount(argv[2])<=0)
	{
		sleep(10);
		continue;
	}
       for(int i = 0;i<1000;i++)
       {   
	   printtime("开始事务");
           svr.tranBegin();
   
           char msgid[32];
           long msgSize=20*1024*1024;
           memset(msgOpts,0,sizeof(CNCCMQ_MSGOPTIONS));
           memset(getOpts,0,sizeof(CNCCMQ_GETOPTIONS));
           msgOpts->msgSize=strnlen(msg, msgSize);
           msgOpts->persistant=true;
           getOpts->transacted=true;
           if(queue.get(msg,msgSize,msgOpts,getOpts)==-1)
           {
               //printf("Get MSG error:%s\n",queue.getErrMsg());
	       //break;
           }
           else
           {
              //printf("Msg Get: id%s size:%ld,rollbackNum:%d Content:%s\n",msgOpts->msgId,msgOpts->msgSize,msgOpts->rollbackNum,msg);
              //printf("%s\n",msg);
	      printtime("获取成功");
              count++;
              if(count%1000==0)
              {
                  printf("Get msg total:%d\n",count);
                  printtime("Time:");
              }
	   }
	   printtime("提交开始");
           svr.commit();
           printtime("提交成功");
       }

       svr.disconnectSvr();
       printf("Get msg total:%d\n",count);
       printtime("Time:");
   }
   free(msgOpts);
   free(getOpts);
   return 0; 
}
