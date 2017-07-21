package group11;

import java.awt.geom.Point2D;
import java.io.Serializable;

import robocode.Bullet;

public class Group11Bullet implements Serializable{

	private Point2D.Double yabai;//自分の位置と敵の位置を入れる
	private Bullet bullet;

	Group11Bullet(Bullet b, Point2D.Double y){
		bullet = b;
		yabai = y;
	}

	public Point2D.Double getYbai(){
		return yabai;
	}

	public Bullet getBullet(){
		return bullet;
	}
}
