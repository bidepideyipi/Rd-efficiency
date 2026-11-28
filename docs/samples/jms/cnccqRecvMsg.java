import org.cnccq.jms.*;

public class cnccqRecvMsg{

	public static void main(String[] args){
		cnccqConnectionFactory connection = new cnccqConnectionFactory("tcp://127.0.0.1:12345");
		cnccqConnection conn = connection.createConnection();
		conn.setQMgrName("QMUMBFEA");
		conn.start();
		cnccqSession ses = new cnccqSession(conn);
		cnccqDestination dest = ses.createQueue("MSGMBFE_1");

		cnccqMessageConsumer cnccqMsg = new cnccqMessageConsumer(dest);
		cnccqMsg.setConnection(conn);
                //for(int i = 0;i<10000;i++)
		//{
			cnccqMessage Msg = cnccqMsg.recv();
			System.out.println("result:"+conn.getResult());
			ses.commit();
			//ses.rollback();
		//}
		conn.close();
		String msg = Msg.getText();
		System.out.println(msg);
	}
}
