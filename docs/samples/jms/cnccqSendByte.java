import org.cnccq.jms.*;

public class cnccqSendByte{
	public static void main(String[] args){
		cnccqConnectionFactory connect = new cnccqConnectionFactory("tcp://127.0.0.1:12345");
		cnccqConnection conn = connect.createConnection();
		conn.setQMgrName("QMUMBFEA");
		conn.start();
                int result = conn.getResult();
                System.out.println("result:"+result);
                if(result != 0)
                {
                    System.out.println("连接失败");
                    return;
                }
		cnccqSession ses = new cnccqSession(conn);
		cnccqDestination dest = ses.createQueue("MSGMBFE_1");
	
		String test = "test byte";	
	//	cnccqMessage msg = new cnccqMessage("test \0 java");
		cnccqMessage msg = new cnccqMessage(test.getBytes());
		msg.setPkgLife(10);
		msg.setMsgPrior(1);
		cnccqMessageProducer Msg = new cnccqMessageProducer(dest);
		Msg.setConnection(conn);
               // for(int i = 0;i<10000;i++)
               // {
			Msg.sendByte(msg);
			ses.commit();
	//	}
		cnccqMessageConsumer consumer = new cnccqMessageConsumer(dest);
		consumer.setConnection(conn);
		int msgNum = consumer.getMsgCount();
		System.out.println("msgNum:"+msgNum);
		System.out.println("getPkgLife:"+msg.getPkgLife());
		System.out.println("getMsgPrior:"+msg.getMsgPrior());
		conn.close();
	}
}
