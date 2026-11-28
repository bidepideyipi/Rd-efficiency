//package org.cnccq.jms;

import org.cnccq.jms.*;

public class connect{

	public static void main(String [] args){
		
		cnccqConnection connection = new cnccqConnection();
		connection.setQMgrName("QMUMBFEA");
		connection.setAddr("127.0.0.1",12345);
		connection.start();
		cnccqSession ses = new cnccqSession(connection);
		System.out.println("result:"+connection.getResult());
		try{
		while(true)
			Thread.sleep(10000);
		}
		catch(Exception e)
		{}
        //        connection.close();
	}
}
