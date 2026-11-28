import org.cnccq.jms.*;

public class cnccqSendMsg{
	public static void main(String[] args){
		cnccqConnectionFactory connect = new cnccqConnectionFactory("tcp://127.0.0.1:12345");
		if(connect.getResult() != 0)
		{
			System.out.println("输入地址错误:"+connect.getErrMsg());
            return;
		}

		cnccqConnection conn = connect.createConnection();
		conn.setQMgrName("QMUMBFEA");
		conn.start();
        if(conn.getResult() != 0)
		{
            System.out.println("连接失败:" + conn.getErrMsg());
            return;
        }

		cnccqSession ses = new cnccqSession(conn);
		cnccqDestination dest = ses.createQueue("MSGMBFE_1");
		if(ses.getResult() != 0)
		{
            System.out.println("打开队列失败:" + ses.getErrMsg());
            return;
        }

		cnccqMessage msg = new cnccqMessage("test  java");
		msg.setPkgLife(10);
		msg.setMsgPrior(1);

		cnccqMessageProducer Msg = new cnccqMessageProducer(dest);
		Msg.setConnection(conn);
		Msg.send(msg);
		if(Msg.getResult() != 0)
		{
            System.out.println("消息发送失败:" + Msg.getErrMsg());
            return;
        }

		ses.commit();
		if(ses.getResult() != 0)
		{
            System.out.println("消息提交失败:" + ses.getErrMsg());
            return;
        }

		cnccqMessageConsumer consumer = new cnccqMessageConsumer(dest);
		consumer.setConnection(conn);
		int msgNum = consumer.getMsgCount();
		System.out.println("msgNum:"+msgNum);
		System.out.println("getPkgLife:"+msg.getPkgLife());
		System.out.println("getMsgPrior:"+msg.getMsgPrior());
		conn.close();
	}
}
