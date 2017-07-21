package group11;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;

import robocode.Bullet;
import robocode.MessageEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.TeamRobot;

/* 更新log:
 * 送信するものは単なるStringに変更した。onMessageReceived()のコメントご覧
 *  (これでRobotData class が serializable じゃなくても O.K.)
 */

/* 今の感想/今後改善すべきと思う点:
 * 1) FriendlyFireで結構被弾している
 * When a target is defined, maintain a position such that no teamates are undergo friendly fire.
 * Otherwise, if a team mate is between the tank and enemy tank, refrain from shooting. [imp]
 *
 * 2) [Not sure]leader cant beat a wall robot in one to one case.. linear target is not accurate due to antigravity maybe
 （多分）wallを叩くときに砲塔回転などの時間があるのでラグが生まれる
 *
 * 3) add one more condition to determine target beside E level:  avg distance of team from the enemy.
 * To give priority to nearer enemies (& hit with powerful bullets)
 * [alternatively] add stronger force towards target to move closer
 弱い弾を遠くから撃ちすぎ
 近づく必要がある（近い敵も撃つ必要がある）
 *
 * 4) Prevent self from being hit my Walls: probably increase the distance (repulsion) from Walls.
 * CHECK walls code properly for a better idea
 wallの弾道予測を行う
 */

public class Group11bot extends TeamRobot {
	public final double PI = Math.PI; // just a constant
	private double firePower; // the power of the shot we will be using
	private double midpointstrength = 0; // The strength of the gravity point in the
									// middle of the field
	private int midpointcount = 0; // Number of turns since that strength was changed.
	private int randompointcnt = 0; //ランダムな位置に重力点を仕掛けるタイミング
	private long[][] randompoint; //重力点を置く座標（後で宣言）
	private double randompointstrength = 0;

	private ArrayList<Group11Bullet> yabaiPoints2dTx = new ArrayList<Group11Bullet>();
	private ArrayList<Group11Bullet> yabaiPoints2dRx = new ArrayList<Group11Bullet>();
	private ArrayList<Bullet> BulletList;		//このArrayListでこのロボットが打った弾全ての情報管理する

	private Hashtable<String, RobotData> RobotList; // 全てのrobot(敵とteammate)の情報を保存する

	ArrayList<GravPoint> Gps = new ArrayList<GravPoint>(2048);

	private String leaderName;		//leader名を保持するためStringで十分.名前しかしようされてない。 前はRobotData classだった
	private  RobotData target; // 現時点でtargetしている敵

	public Group11bot(){
		randompoint = new long[2][10];
	}

	public void run() {

		RobotList = new Hashtable<String, RobotData>();
		BulletList = new ArrayList<Bullet>();

		target = new RobotData();
		leaderName = new String();

		// the next two lines mean that the turns of the robot, gun and radar are independent
		setAdjustGunForRobotTurn(true);
		setAdjustRadarForGunTurn(true);
		turnRadarRightRadians(2 * PI); // turns the radar right around to get a view of the field

		if (getEnergy() >= 190) {
			leaderName = getName();
			System.out.println("I became Leader!");
			try {
				// Send leader's name to entire team
				broadcastMessage(leaderName);
			} catch (IOException ignored) {
			}
		}

		target.distance = 100000; // initialise the distance so that we can select a target*

		while (true) {
			if (isLeader()) {
				updateEnemy(); // updateの順番変えたらエラーの可能性
				updateLeader();
				//sets the color of leader
				setColors(Color.green, Color.black, Color.red);
			} else{
				//sets the color of subs
				setColors(Color.green, Color.white, Color.red);
			}

			antiGravMove(); // Move the bot
			doFirePower(); // select the fire power to use
			doScanner(); // Oscillate the scanner over the bot
			doGun();	//move the gun to predict where the enemy will be
			System.out.println("target: " + target.name + " target.distance: " + target.distance);
			Bullet b = fireBullet(firePower);
			execute(); // execute all commands
			if(b!=null){
				BulletList.add(b);
				updateBulletPosition();
			}
			for(Group11Bullet g11 : yabaiPoints2dTx){
				if(g11!=null){
					try {
						broadcastMessage(g11);
					} catch (IOException ignored) {
					}
				}
			}

			for(Bullet bu : BulletList){
				if(bu!=null){
					try {
						if(!bu.isActive())
						broadcastMessage(bu);
					} catch (IOException ignored) {
					}
				}
			}

		}
	}

	/**	Friendly fire を防止する作戦
	 * 1) かくロボットが自分が打った弾を全て保持する, BulletList<Bullet>に
	 * 2) かく弾の位置を予測し、危険な点を仲間に教える, yabaiPoints2D<Point2D.Double>に
	 * 		debugのためonPaint()でyabaiPoints2Dの点を描く。が、点が正しく表示されない
	 * 3)　仲間がそれらの危険な点から離れる (やり方わかってない）
	 */

	//to update the positions of each bullet
	//空中の弾全ての位置を予測する
	public void updateBulletPosition(){
		yabaiPoints2dTx.clear();

		for(Bullet b: BulletList){
			if(b == null) continue;

			if(b.isActive()){
				Point2D.Double p = new Point2D.Double();
				new Group11Bullet(b,p);
				//弾の位置を予測する
				p.x = b.getX() + Math.cos(b.getHeadingRadians()) * b.getVelocity() * 5 ;
				p.y = b.getY() + Math.sin(b.getHeadingRadians()) * b.getVelocity() * 5;
				System.out.println("pt "+ p.x + " "+ p.y);
				yabaiPoints2dTx.add(new Group11Bullet(b,p));
			}
		}
	}
	// 通信用に変更
	public void onMessageReceived(MessageEvent msg) {
		// teammateがメッセージを受信し処理する
		//メッセージで受けた弾がこれから通る点をyabaiPoints2Dに追加
		if(msg.getMessage() instanceof Group11Bullet){
			Group11Bullet g11 = (Group11Bullet) msg.getMessage();
			yabaiPoints2dRx.add(g11);
		}

		if(msg.getMessage() instanceof Bullet){
			Bullet b = (Bullet) msg.getMessage();
			Iterator<Group11Bullet> i = yabaiPoints2dRx.iterator();
			while(i.hasNext()){
				Group11Bullet g11 = i.next();
	            if(g11.getBullet().toString().equals(b.toString())){
	                i.remove();
	            }
	        }
		}
		/*
		 * 各botが独立にRobotListデータ管理しているため、名前だけ送信すればO.K.
		 * 各botが自分がもつRobotListデータを使用し、反重力・射撃等に使う
		 * (これでRobotData class が serializable じゃなくても O.K.)
		 */
		if (msg.getMessage() instanceof String) {
			String bogeyName = (String) msg.getMessage();

			// targetかleaderの名前を更新する
			if (isTeammate(bogeyName)) {
				leaderName = bogeyName;
				System.out.println("[incoming message]Leader.name " + leaderName);
				if (isLeader())
					System.out.println("I became Leader!");
			} else {
				//pull out the RobotList data associated with botname 'bogeyName'
				//追加：　bogeyName名前でハッシュされたレコードをRobotListから取り出す
				target = (RobotData) RobotList.get(bogeyName);

				//target.name = bogey.name;
				System.out.println("[incoming message]New target name  " + target.name);
			}
		}
	}

	public void doFirePower() {
		firePower = 400 / target.distance;// selects a bullet power based on the distance away from the target
		if (firePower > 3) {
			firePower = 3;
		}
	}

	public void antiGravMove() {
		double xforce = 0;
		double yforce = 0;
		double force;
		double ang;
		GravPoint p;
		RobotData en;
		Enumeration<RobotData> e = RobotList.elements();
		// cycle through all the enemies. If they are alive, they are repulsive.
		// Calculate the force on us
		//全体的に定数で決めたほうが強くなるイメージです
		Gps.clear();

		while (e.hasMoreElements()) {
			en = (RobotData) e.nextElement();
			if (en.live) {
				double enemypointstrength;
				if (target.name != null && target.name.compareTo(en.name) == 0)
				//current targetに対して引力
					enemypointstrength = 1500;
				else if (isTeammate(target.name))
					enemypointstrength = -1500;
				else
					enemypointstrength = -2000;
				p = new GravPoint(en.x, en.y, enemypointstrength);
				Gps.add(p);
				force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 2);
				// Find the bearing from the point to us
				ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
				// Add the components of this force to the total force in their
				// respective directions
				xforce += Math.sin(ang) * force;
				yforce += Math.cos(ang) * force;
			}
		}

		/**
		 * 5ターンごとに真ん中に重力点を設置する（武村）
		 **/

		midpointcount++;
		randompointcnt++;
		if (midpointcount > 5) {
			midpointcount = 0;
			midpointstrength = -1 * ((Math.random() * 500) + 1000);
		}
		p = new GravPoint(getBattleFieldWidth() / 2, getBattleFieldHeight() / 2, midpointstrength);
		force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
		ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
		xforce += Math.sin(ang) * force;
		yforce += Math.cos(ang) * force;

		/*
		*10ターンごとにランダムな位置に反重力点を追加（武村）
		*/
		if(randompointcnt > 10){
			randompointcnt = 0;
			randompointstrength = ((Math.random()*500) + 700);
			for(int i=0;i<10;i++){
				randompoint[0][i]=Math.round(Math.random()*getBattleFieldWidth());
				randompoint[1][i]=Math.round(Math.random()*getBattleFieldHeight());
			}
		}

		for(int i=0;i<10;i++){
			p = new GravPoint(randompoint[0][i],randompoint[1][i],randompointstrength);
			force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
			ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
			xforce += Math.sin(ang) * force;
			yforce += Math.cos(ang) * force;
		}

		if(yabaiPoints2dRx != null){
		for(Group11Bullet g11 : yabaiPoints2dRx){
			if(g11 != null){
				p = new GravPoint(g11.getYbai().x,g11.getYbai().y,20);
				force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
				force = -Math.pow(force, 2);
				ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
				xforce += Math.sin(ang) * force;
				yforce += Math.cos(ang) * force;
			}
		}
		}

		// ニタくんから
		/**
		 * adds a middle point on walls with 500 strength. The strength changes
		 * every 5 turns, and goes between -1000 and 1000. This gives a movement
		 * that a bot doesn't hit walls.
		 * every 5 turnsではなく毎ターン設定されていると思います（武村）
		 **/

		for (int i = 0; i < 11; i++) {

			p = new GravPoint(0.1 * i * getBattleFieldWidth(), 0, -200 - Math.abs(10 - i*2) * 15);
			force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
			ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
			xforce += Math.sin(ang) * force;
			yforce += Math.cos(ang) * force;

			p = new GravPoint(getBattleFieldWidth(), 0.1 * i * getBattleFieldHeight(), -200 - Math.abs(10 - i*2) * 15);
			force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
			ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
			xforce += Math.sin(ang) * force;
			yforce += Math.cos(ang) * force;

			p = new GravPoint(0.1 * i * getBattleFieldWidth(), getBattleFieldHeight(), -200 - Math.abs(10 - i*2) * 15);
			force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
			ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
			xforce += Math.sin(ang) * force;
			yforce += Math.cos(ang) * force;

			p = new GravPoint(0, 0.1 * i * getBattleFieldHeight(), -200 - Math.abs(10 - i*2) * 15);
			force = p.power / Math.pow(getRange(getX(), getY(), p.x, p.y), 1.5);
			ang = normaliseBearing(Math.PI / 2 - Math.atan2(getY() - p.y, getX() - p.x));
			xforce += Math.sin(ang) * force;
			yforce += Math.cos(ang) * force;
		}
		/**
		 * The following four lines add wall avoidance. They will only affect us
		 * if the bot is close // to the walls due to the force from the walls
		 * decreasing at a power 3.
		 **/
		xforce += 5000 / Math.pow(getRange(getX(), getY(), getBattleFieldWidth(), getY()), 3);
		xforce -= 5000 / Math.pow(getRange(getX(), getY(), 0, getY()), 3);
		yforce += 5000 / Math.pow(getRange(getX(), getY(), getX(), getBattleFieldHeight()), 3);
		yforce -= 5000 / Math.pow(getRange(getX(), getY(), getX(), 0), 3);

		// Move in the direction of our resolved force.
		goTo(getX() - xforce, getY() - yforce);
	}

	/** Move towards an x and y coordinate **/
	public void goTo(double x, double y) {
		double dist = 20;
		double angle = Math.toDegrees(absbearing(getX(), getY(), x, y));
		double r = turnTo(angle);
		setAhead(dist * r);
	}

	/**
	 * Turns the shortest angle possible to come to a heading, then returns the
	 * direction the the bot needs to move in.
	 **/
	public int turnTo(double angle) {
		double ang;
		int dir;
		ang = normaliseBearing(getHeading() - angle);
		if (ang > 90) {
			ang -= 180;
			dir = -1;
		} else if (ang < -90) {
			ang += 180;
			dir = -1;
		} else {
			dir = 1;
		}
		setTurnLeft(ang);
		return dir;
	}

	/** keep the scanner turning **/
	public void doScanner() {
		setTurnRadarLeftRadians(2 * PI);
	}

	/** Move the gun to the predicted next bearing of the enemy **/
	//微分の要領でより正確な将来位置を計算？　Wall相手では明らかな改善
	//getTime...イベントが起きてからの時間を返します
	public void doGun() {
		long time;
		long nextTime;
		Point2D.Double p;

		p = new Point2D.Double(target.x,target.y);

		for(int i=0;i<10;i++){
			nextTime = (int)(Math.round((getRange(getX(), getY(), p.x, p.y)/(20 - (3*firePower)))));
			time = getTime() + nextTime;
			p = target.guessPosition(time);
		}

		// offsets the gun by the angle to the next shot based on linear
		// targeting provided by the enemy class
		double gunOffset = getGunHeadingRadians() - (Math.PI / 2 - Math.atan2(p.y - getY(), p.x - getX()));
		setTurnGunLeftRadians(normaliseBearing(gunOffset));
	}

	// if a bearing is not within the -pi to pi range, alters it to provide the
	// shortest angle
	private double normaliseBearing(double ang) {
		if (ang > PI)
			ang -= 2 * PI;
		if (ang < -PI)
			ang += 2 * PI;
		return ang;
	}

	// if a heading is not within the 0 to 2pi range, alters it to provide the
	// shortest angle
	/*
	private double normaliseHeading(double ang) {
		if (ang > 2 * PI)
			ang -= 2 * PI;
		if (ang < 0)
			ang += 2 * PI;
		return ang;
	}*/

	// returns the distance between two x,y coordinates
	//2点(x1,y1)(x2,y2)の距離を求める
	private double getRange(double x1, double y1, double x2, double y2) {
		double xo = x2 - x1;
		double yo = y2 - y1;
		double h = Math.sqrt(xo * xo + yo * yo);
		return h;
	}

	// gets the absolute bearing between to x,y coordinates
	private double absbearing(double x1, double y1, double x2, double y2) {
		double xo = x2 - x1;
		double yo = y2 - y1;
		double h = getRange(x1, y1, x2, y2);
		if (xo > 0 && yo > 0) {
			return Math.asin(xo / h);
		}
		if (xo > 0 && yo < 0) {
			return Math.PI - Math.asin(xo / h);
		}
		if (xo < 0 && yo < 0) {
			return Math.PI + Math.asin(-xo / h);
		}
		if (xo < 0 && yo > 0) {
			return 2.0 * Math.PI - Math.asin(-xo / h);
		}
		return 0;
	}

	/**
	 * onScannedRobot: What to do when you see another robot
	 */
	public void onScannedRobot(ScannedRobotEvent e) {

		RobotData bogey;

		if (RobotList.containsKey(e.getName())) {
			// すでに登録されていた場合、情報更新のため参照する
			bogey = (RobotData) RobotList.get(e.getName());
		} else {
			bogey = new RobotData();
			RobotList.put(e.getName(), bogey); // bogey を hashtableに追加する
			bogey.name = e.getName();
		}
		bogey.energy = e.getEnergy(); // leaderupdate(),targetupdate()のため保持する
		bogey.live = true;

		// すでに登録されていたため、情報更新のため参照する
		bogey = (RobotData) RobotList.get(e.getName());

		// the next line gets the absolute bearing to the point where the bot is
		double absbearing_rad = (getHeadingRadians() + e.getBearingRadians()) % (2 * PI);
		// this section sets all the information about our target
		bogey.name = e.getName();
		if ((bogey.energy - e.getEnergy()) > 0 && (bogey.energy - e.getEnergy()) < 4)
			bogey.fire = true;
		else
			bogey.fire = false;
		bogey.energy = e.getEnergy();
		double h = normaliseBearing(e.getHeadingRadians() - bogey.heading);
		h = h / (getTime() - bogey.ctime);
		bogey.changehead = h;
		bogey.x = getX() + Math.sin(absbearing_rad) * e.getDistance(); // works out the x coordinate of where the target is
		bogey.y = getY() + Math.cos(absbearing_rad) * e.getDistance(); // works out the y coordinate of where the target is
		bogey.bearing = e.getBearingRadians();
		bogey.headingaccel = e.getHeadingRadians() - bogey.heading; //角速度を取得
		bogey.heading = e.getHeadingRadians();
		bogey.ctime = getTime(); // game time at which this scan was produced
		bogey.accel = e.getVelocity() - bogey.speed; //加速度を取得
		bogey.speed = e.getVelocity();
		bogey.distance = e.getDistance();
		bogey.live = true;
	}

	public void updateLeader() {
		RobotData bot;
		Enumeration<RobotData> e = RobotList.elements();
		double least = getEnergy();
		int count =0;
		// 生きているteammateのenergyに目を通す

		while (e.hasMoreElements()) {
			bot = (RobotData) e.nextElement();
			// System.out.println("list " +bot.name);
			// 40.0以下のbotリーダになれない
			if (bot.live && isTeammate(bot.name) && bot.energy <= 40) {
				count++;
				if (bot.energy < least) {
					least = bot.energy;
					leaderName = bot.name;
				}
			}
		}

		if(count ==0){
			leaderName=getName();
		}

		System.out.println("[Sending message] New leader: " + leaderName);
		try {
			// Send leaderName to entire team
			broadcastMessage(leaderName);	//Stringだけ送る
		} catch (IOException ignored) {
		}
	}

	public void updateEnemy() {

		RobotData bot;
		if (target.name != null)
			bot = (RobotData) RobotList.get(target.name);
		else {
			bot = new RobotData();
			bot.live = false;
		}

		if (!bot.live) {
			Enumeration<RobotData> e = RobotList.elements();
			boolean  canDecide = false;//仮のtargetを決められるか?
			while(e.hasMoreElements()){
				bot = (RobotData) e.nextElement();
				System.out.println("Cehcking:" + bot.name);
				if (bot.live && !isTeammate(bot.name) ) {
					if(!canDecide){//仮のtargetを決めていなければ，botをtargetにする
						target = bot;
						canDecide = true;
					}
					if (target.energy > bot.energy) { //botをtargetにする
						target = bot;		//include all data. これからleaderがbotのデータをtargetとして扱う
					}
				}
			}

			System.out.println("[Sending message] Our new target is " + target.name);

			try {
				// Send target name to our entire team
				broadcastMessage(target.name); 	//オブジェクトじゃなくて名前だけ送る
			} catch (IOException ignored) {
			}
		}
	}

	public boolean isLeader() {
		// System.out.println("just checking leader name " + leaderName);
		//leaderNameがnullかチェックしないとNUllPointerException発生可能性
		if(leaderName == null)
			return false;
		else if (leaderName.equals(getName()))
			return true;
		else
			return false;
	}

	public void onRobotDeath(RobotDeathEvent e) {

		RobotData en = (RobotData) RobotList.get(e.getName());

		en.live = false;

		// leader自身が死んだ時
		if (e.getName().equals(leaderName)) {
			System.out.println("Our leader died :'(");
			updateLeader();
		} else if (e.getName().equals(target.name)) {
			System.out.println("My target " + e.getName() + " just died");
			updateEnemy(); // updates the target.
			//When updateEnemy() assigned to only Leader, if only one survivor, enemyUpdate doesnt occur
		}else if(isTeammate(e.getName())){
			Iterator<Group11Bullet> i = yabaiPoints2dRx.iterator();
			while(i.hasNext()){
				Group11Bullet g11 = i.next();
	            if(g11.getBullet().getName().equals(e.getName())){
	                i.remove();
	            }
	        }
		}
	}
}