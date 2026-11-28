import org.cnccq.jms.*;

public class connectFa{

	public static void main(String [] args){
		cnccqConnectionFactory cnccqcoFa = new cnccqConnectionFactory("tcp//127.0.0.1:12345");
		cnccqConnection connect = cnccqcoFa.createConnection();
		connect.setQMgrName("QMUMBFEA");
		int i = 0;
		for(i = 0 ;i<100000;i++){
		connect.start();
		int res = connect.getResult();
		if(res != 0)
			System.out.println("result:"+res+"i:"+i);
		connect.close();
		res = connect.getResult();
		if(res != 0)
			System.out.println("result:"+res+"i:"+i);
		if(i%100 == 0)
			System.out.println("success i:"+i);
		}
	}
}
