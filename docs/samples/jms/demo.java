import org.cnccq.jms.*;
 
public class demo{

	public static void main(String[] args) throws Exception{
		isBigEndian isbig = new isBigEndian();
		if(!isbig.isBig())
			System.out.println("LITTLE");
		else
			System.out.println("BIG");
	}
}
