package group11;

import java.awt.geom.Point2D;

public class RobotData{// implements java.io.Serializable {

	public String name; // name of bot
	public double energy; // botのエネルギー

	boolean fire; // nita kun
	public double bearing, heading,headingaccel, speed,accel, x, y, distance, changehead;
	public long ctime; // game time that the scan was produced　最期にスキャンされたときの時刻
	public boolean live; // // botが生きているかどうか?

	//時刻whenでの敵の予測位置
	//円形方式の位置合わせを利用
	public Point2D.Double guessPosition(long when) {
		double diff = when - ctime;
		double newX,newY;

		if(Math.abs(headingaccel) > 0.00001)
		{
			double radius = speed/headingaccel;
			double tothead = diff * headingaccel;
			newY = y + (Math.sin(heading+tothead) * radius)-(Math.sin(heading) * radius);
			newX = x + (Math.cos(heading) * radius) - (Math.cos(heading + tothead) * radius);
		}else{
			newY = y + Math.cos(heading) * speed * diff;
			newX = x + Math.sin(heading) * speed * diff;
		}
		return new Point2D.Double(newX, newY);
	}
}