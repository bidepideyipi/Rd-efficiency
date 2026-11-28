#include "../../msgsvr/interface/cas/cas-clt.h"
int main()
{
    const char *QMgrName = "testB";
    const char *addr = "0.0.0.0";
    int port = 12345;
    const char *QueName = "test_1B";
    
    cnccSvr  stSvr;
	memset(&stSvr,0,sizeof(cnccSvr));
    int iRet = 0;
    iRet = cnccSvrInit(&stSvr); 
    if (0 != iRet)
	{
        printf("初始化cnccSvr失败!\n");
        return -1;            
    }
	
	cnccQueue stQueue ;
	memset(&stQueue,0,sizeof(cnccQueue));
    iRet = cnccQueueInit(&stSvr,&stQueue);
    if(0 != iRet)
    {
        printf("cnccQueueInit failed!\n");
        return -1;
    }
	
    bool bRet = false;
	bRet = connectSvrEx(&stSvr,QMgrName,addr, port,NULL);
	//若调用的应用与cnccq在同一台机器上，则可以不需要地址和端口参数使用connectSvr接口
	//bRet = connectSvr(&stSvr,QmgrName,NULL);
	if(!bRet)
	{
		printf("connectSvr失败！\n");
		printf("errcode:%d,errmsg:%s\n",stSvr.errcode,errstr(stSvr.errcode));
		return -1;
	}	
	
	char *pSaveMsg = NULL;
	pSaveMsg = (char*)malloc(20*1024*1024);// 当前消息最大支持20M
	if(NULL == pSaveMsg)
	{
	    printf("分配内存失败！\n");
		return -1;
	}
	
    bRet = openQueue(&stQueue,QueName,0,NULL,NULL);
    if(!bRet)
	{
        printf("打开队列失败！\n");
        printf("errcode:%d,errmsg:%s\n",stQueue.errcode,errstr(stQueue.errcode));
        return -1;
	}

	CNCCMQ_MSGOPTIONS stMsgopt ;
    CNCCMQ_GETOPTIONS stGetopt ;
	 
//发送10个消息
int i = 0;
    for (i = 0; i < 10; i++) 
	{
	    if(0 == getMsgCount(&stSvr,QueName,NULL,NULL))
		{
		    sleep(1);
			continue;
		}
		bRet = tranBegin(&stSvr,NULL);
        if (!bRet) 
		{
            printf("开始事务失败\n");
            printf("errcode:%d,errmsg:%s\n", stSvr.errcode, errstr( stSvr.errcode));
            return -1;                        
        }
        memset(&stMsgopt,0,sizeof(CNCCMQ_MSGOPTIONS));
        memset(&stGetopt,0,sizeof(CNCCMQ_GETOPTIONS));
        memset(pSaveMsg,0,20*1024*1024);

        
        bRet = get(&stQueue, pSaveMsg,20*1024*1024,&stMsgopt, &stGetopt);
        if (!bRet) 
		{
            printf("put失败!\n");
            printf("errcode:%d,errmsg:%s\n", stQueue.errcode, errstr(stQueue.errcode));
            return -1;
        }
		
		// 这里对消息进行处理，若处理失败，可执行svr->rollback而不执行svr->commit
		printf("msginfo is: %s\n",pSaveMsg);
		
        bRet = commit(&stSvr,NULL);
        if (!bRet) 
		{
            printf("提交事务失败\n");
            printf("errcode:%d,errmsg:%sn", stSvr.errcode, errstr(stSvr.errcode));
            return -1;
        }
    }
    bRet = closeQueue(&stQueue,NULL);
    if (!bRet) 
	{
        printf("关闭队列失败!\n");
        printf("errcode:%d,errmsg:%s\n", stQueue.errcode, errstr(stQueue.errcode));
        rollback(&stSvr,NULL);    // 失败后可进行回退。
        return -1;
    }

    disconnectSvr(&stSvr,NULL);
	
    return 0;
}