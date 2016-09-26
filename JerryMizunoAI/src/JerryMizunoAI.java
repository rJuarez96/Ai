
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;
import java.util.Vector;

import support.Command;
import enumerate.Action;
import enumerate.AttackAction;
import enumerate.Position;
import enumerate.State;
import simulator.Simulator;
import structs.*;
import gameInterface.AIInterface;

/*
 * knnPrediction_Simulation_AI
 * Implemented by Chu Chun Yin
 * 
 * This AI is created based on the knn prediction and simulation AI and Fuzzy Control AI,
 * as proposed by Yamamoto et al (2014) and Chu et al (2015) respectively.
 * 
 * Since this AI makes use of the Simulator package, which is only available after FightingICE ver. 1.22, 
 * this AI will not work with lower versions of FightingICE.
 * 
 * For researchers, please note that this source code is different from
 * the code that was used in the testing and evaluation in the papers 
 * authored by Yamamoto et al (2014) and Chu et al (2015),
 * and thus may not behave in the same manner.
 * 
 * Furthermore, this AI is adjusted for ZEN, and may not work well with other characters.
 */

public class JerryMizunoAI implements AIInterface {
	// whether to use fuzzy control and rule-base, as prescribed in the "Applying Fuzzy Control in Fighting Game AI" paper
	private static final boolean useFuzzy = true;
	// parameters for fuzzy control
	int minOppData = 5;	
	int upperOppData = 15;
	int minDistance = 40;
	int upperDistance = 80;
	// parameters for k-nn
	private static final int K_DISTANCE = 50;
	private static final double K_THRESHOLD = 0.3;
	private static final int THRESHOLD = 3;
	private static final int DELAY = 14;
	// player's boolean number, P1 = true, P2 = false
	boolean p;

	GameData gd;
	Key inputKey;
	FrameData fd;
	
	CharacterData my;
	CharacterData opp;
	
	Position pos;
	
	// deque for retaining ActData the opponent conducted
	Deque<ActData> oppActData_GG;
	Deque<ActData> oppActData_GA;
	Deque<ActData> oppActData_AG;
	Deque<ActData> oppActData_AA;
	
	// deque for retaining Ground and Air Actions
	Deque<Action> G_Act;
	Deque<Action> A_Act;
	
	Deque<Action> myAct;
	Deque<Action> oppAct;
	int[] checkAct;
	
	Deque<KeyData> inputLog;
	
	Command cc;
	Simulator simulator;
	
	Action preOppAct;
	Action nowOppAct;
	
	int preRound;
	int nowRound;
	
	long time;
	
	@Override
	public synchronized int initialize(GameData gameData, boolean playerNumber) {

		gd = gameData;
		p = playerNumber;
		
		inputKey = new Key();
		fd = new FrameData();
		cc = new Command();
		
		preOppAct = Action.NEUTRAL;
		nowOppAct = Action.NEUTRAL;
		
		preRound = 0;
		nowRound = 0;

		simulator = gd.getSimulator();
		this.inputLog = new LinkedList<KeyData>();
		this.oppActData_GG = new LinkedList<ActData>();
		this.oppActData_GA = new LinkedList<ActData>();
		this.oppActData_AG = new LinkedList<ActData>();
		this.oppActData_AA = new LinkedList<ActData>();
		this.myAct = new LinkedList<Action>();
		this.oppAct = new LinkedList<Action>();
		checkAct = new int[EnumSet.allOf(Action.class).size()];
		
		setAirGroundAction();
		System.out.println("this.p = " + this.p);
		
		return 0;
	}

	@Override
	public synchronized void getInformation(FrameData frameData) {
		time = System.currentTimeMillis();
		fd = frameData;
		cc.setFrameData(fd, p);
		if(p){
			my = fd.getP1();
			opp = fd.getP2();
		}else{
			my = fd.getP2();
			opp = fd.getP1();
		}
		if(fd.getKeyData() != null) inputLog.addLast(fd.getKeyData());
		
		for(int i = 0 ; i < EnumSet.allOf(Action.class).size() ; i++){
			checkAct[i] = 0;
		}
		
		nowRound = fd.getRound();
		if(nowRound != preRound && nowRound != 0 && nowRound%3 == 0){
			oppActData_GG.clear();
			oppActData_GA.clear();
			oppActData_AG.clear();
			oppActData_AA.clear();
		}
	}

	@Override
	public synchronized void processing() {
		boolean temp;
		
		inputKey.empty();
		if(!fd.getEmptyFlag()){	
			if(fd.getRemainingTime() > 0){
				
				nowOppAct = opp.getAction();
				Vector<MotionData> oppMotion = this.p? gd.getPlayerTwoMotion():gd.getPlayerOneMotion();

				if(oppMotion.elementAt(opp.getAction().ordinal()).getFrameNumber() == opp.getRemainingFrame()){
					try{
							AttackAction.valueOf(nowOppAct.name());
							
							if(my.isFront()){
								ActData act = new ActData(opp.getX()-my.getX(),opp.getY()-my.getY(),nowOppAct);
								setOppAttackData(act);
							}else{
								ActData act = new ActData(my.getX()-opp.getX(),my.getY()-opp.getY(),nowOppAct);
								setOppAttackData(act);
							}
						}catch (Exception e){
					}
				}
					
				{
					if(my.getX() < opp.getX()) temp=true;
					else temp=false;
				}

				// update the frameData by 15 frames to predict the positions after delay
				FrameData sim_fd = simulator.simulate(fd, this.p, null, null, DELAY);
				// set the CharacterData after delay
				my 	= (this.p)? sim_fd.getP1():sim_fd.getP2();
				opp = (this.p)? sim_fd.getP2():sim_fd.getP1();
							
				setPosition();
				setMyAct();

				if(cc.getskillFlag()){
					inputKey = cc.getSkillKey();
				}else{
					if(cc.getMyCharacter().isControl() || my.getRemainingFrame() <= 0){
						// default action
						Action act = Action.CROUCH_GUARD;
						
						// decide whether to use mizunoAI or not
						int oppData = (opp.getState() == State.AIR)? oppActData_GA.size():oppActData_GG.size();
						double decision_useMizunoAI = (double) (oppData - minOppData) / (upperOppData - minOppData);
						
						if (!useFuzzy || (useFuzzy && (Math.random() < decision_useMizunoAI))){
							// use knn prediction and simulation
							if(calculateActDistance(getOppAttackData(),opp.getX()-my.getX(),opp.getY()-my.getY())){
								Deque<Action> myAction = new LinkedList<Action> ();
								Deque<Action> opAction = new LinkedList<Action> ();
								
								// hp before simulation
								int my_original_hp = my.getHp();
								int op_original_hp = opp.getHp();
								
								Action nowAction;
								
								// frameData after the simulation
								FrameData simulatedFrame;
								// evaluated value calculated by the difference of HP after simulation
								int best_score = 0;
								int temp_score = 0;
								
								// simulate a situation when this AI conducts an action against the predicted action by k-nn.
								// execute the simulation by a round robin
								for(Iterator<Action> i = myAct.iterator();i.hasNext();){
									myAction.clear();
									nowAction = i.next();
									myAction.add(nowAction);
									for(Iterator<Action> j = oppAct.iterator();j.hasNext();){
										opAction.clear();
										opAction.add(j.next());
										
										// simulate the game for 60 frames
										simulatedFrame = simulator.simulate(sim_fd, this.p, myAction, opAction, 60);
										// calculate the evaluation value by the difference of two character's HP
										temp_score = (this.p)? (simulatedFrame.getP1().hp - my_original_hp) - (simulatedFrame.getP2().hp - op_original_hp) :
													 (simulatedFrame.getP2().hp - my_original_hp) - (simulatedFrame.getP1().hp - op_original_hp);
										
										// conduct an action with highest evaluation value
										if(temp_score > best_score){
											act = nowAction;
											best_score = temp_score;
										}
									}
								}
							}
						}
						else{
							// use fuzzy logic to fight
							act = fuzzyFight();
						}
						
						if(my.getAction() == Action.DOWN) my.setFront(temp);
						cc.commandCall(act.name());
						inputKey = cc.getSkillKey();
					}
				}
			}
		}
		fin();
	}

	private Action fuzzyFight() {
		// the AI has not accumulated enough data for action prediction
		// use fuzzy control to determine attack range
		Action act = Action.CROUCH_GUARD;
				
		int distance = Math.abs(opp.getX()-my.getX());
		double decision_attackRange = (double) (distance - minDistance) / (upperDistance - minDistance);
		decision_attackRange = Math.min(decision_attackRange, 1.0);
		decision_attackRange = Math.max(decision_attackRange, 0.0);
								
		Random rand = new Random();
		int temp = rand.nextInt(3);
		if (opp.getState() != State.AIR){
			if (decision_attackRange < Math.random()) {
				// close range, to ground
				switch (temp){
					case 0 :
						act = Action.STAND_FB;
						break;
					case 1 :
						act = Action.CROUCH_B;
						break;
					case 2 :
						act = (my.getEnergy()>5 && opp.getState()!=State.DOWN)? Action.STAND_A : Action.THROW_A;
						break;
					}
			}
			else {
				// long range, to ground
				switch (temp){
				case 0 :
					act = (my.getEnergy()>30 && opp.getState()!=State.DOWN)? 
							Action.STAND_D_DF_FB:Action.STAND_D_DF_FA;
					break;
				case 1 :
					act = (my.getEnergy()>50 && opp.getState()!=State.DOWN)? 
							Action.STAND_D_DB_BB:Action.STAND_D_DB_BA;
					break;
				case 2 :
					act = Action.CROUCH_FB;
					break;
				}
			}
			}
			else if (decision_attackRange < Math.random()) {
				// close range, to air
				switch (temp){
					case 0 :
						act = Action.CROUCH_FA;
						break;
					case 1 :
						act = Action.STAND_FB;
						break;
					case 2 :
						act = (my.getEnergy()>50 && opp.getState()!=State.DOWN)? 
								Action.STAND_F_D_DFB:Action.STAND_F_D_DFA;
						break;
				}
				} else {
				// long range, to air
				switch (temp){
					case 0 :
						act = (my.getEnergy()>50 && opp.getState()!=State.DOWN)? 
								Action.AIR_D_DF_FB:Action.AIR_D_DF_FA;
						break;
					case 1 :
						act = Action.AIR_UB;
						break;
					case 2 :
						act = (my.getEnergy()>50 && opp.getState()!=State.DOWN)? 
								Action.STAND_F_D_DFB:Action.STAND_F_D_DFA;
						break;
					}
				}
		
		return act;
	}

	@Override
	public synchronized Key input() {
		time = -time + System.currentTimeMillis();
		return inputKey;
	}

	@Override
	public synchronized void close() {
		oppActData_GG.clear();
		oppActData_GA.clear();
		oppActData_AG.clear();
		oppActData_AA.clear();
	}
	
	private synchronized void fin(){
		preOppAct = nowOppAct;
		preRound = nowRound;
		oppAct.clear();
	}
	
	private synchronized boolean calculateActDistance(Deque<ActData> actData , int x, int y){
		int threshold = (int)(actData.size()*K_THRESHOLD + 1);
		Deque<ActData> temp = new LinkedList<ActData>();
		ActData[] array;
		
		for(Iterator<ActData> i = actData.iterator() ; i.hasNext() ; ){
			ActData act = new ActData(i.next());
			if(my.isFront()) act.setDistance((int)Math.sqrt((act.getX()-x)*(act.getX()-x)+(act.getY()-y)*(act.getY()-y)));
			else act.setDistance((int)Math.sqrt((act.getX()+x)*(act.getX()+x)+(act.getY()+y)*(act.getY()+y)));
			if( act.getDistance() < K_DISTANCE) temp.add(act);
		}
		if(temp.size() < Math.min(threshold,THRESHOLD)) return false;		
		
		array = new ActData[temp.size()];
		array = actSort(temp);
		setOppAct(array, Math.min(threshold,THRESHOLD));
		
		return true;
	}
	
	private synchronized ActData[] actSort(Deque<ActData> actData){
		ActData[] array = new ActData[actData.size()];
		
		for(int i = 0 ; i < array.length ; i ++){
			array[i] = new ActData(actData.pop());
		}
		sort(array);
		
		return array;
	}
	
	private synchronized void merge(ActData[] a1,ActData[] a2,ActData[] a){
		int i=0,j=0;
		while(i<a1.length || j<a2.length){
			if(j>=a2.length || (i<a1.length && a1[i].getDistance()<a2[j].getDistance())){
				a[i+j].setMenber(a1[i]);
				i++;
			}
			else{
				a[i+j].setMenber(a2[j]);
				j++;
			}
		}
	}

	private synchronized void mergeSort(ActData[] a){
		if(a.length>1){
			int m=a.length/2;
			int n=a.length-m;
			ActData[] a1=new ActData[m];
			ActData[] a2=new ActData[n];
			for(int i=0;i<m;i++) a1[i] = new ActData(a[i]);
			for(int i=0;i<n;i++) a2[i] = new ActData(a[m+i]);
			mergeSort(a1);
			mergeSort(a2);
			merge(a1,a2,a);
		}
	}

	private synchronized void sort(ActData[] a){
		mergeSort(a);
	}
	
	private synchronized void setMyAct(){
		if(my.getState() == State.AIR) myAct = A_Act;
		else myAct = G_Act;		
	}
	
	private synchronized void setOppAct(ActData[] array,int threshold){
		Action[] subAct = Action.values();
		int max = 1;
		
		for(int i = 0 ; i < threshold ; i++){
			checkAct[array[i].getAct().ordinal()] ++;
		}
		
		for(int i = 0 ; i < EnumSet.allOf(Action.class).size() ; i++){
			if(checkAct[i] > max){
				oppAct.clear();
				oppAct.add(subAct[i]);
				max = checkAct[i];
			}
			else if(checkAct[i] == max){
				oppAct.add(subAct[i]);
			}
		}
	}
	
	private synchronized void setPosition(){
		if(my.getState() == State.AIR){
			if(opp.getState() == State.AIR) pos = Position.Air_Air;
			else pos = Position.Air_Ground;
		}else{
			if(opp.getState() == State.AIR) pos = Position.Ground_Air;
			else pos = Position.Ground_Ground;
		}
	}
	
	private synchronized Deque<ActData> getOppAttackData(){
		switch(pos){
		case Air_Air: return oppActData_AA;
		case Air_Ground: return oppActData_AG;
		case Ground_Air: return oppActData_GA;
		case Ground_Ground: return oppActData_GG;
		}
		return oppActData_GG;
	}
	
	private synchronized void setOppAttackData(ActData act){
		switch(pos){
		case Air_Air: oppActData_AA.add(act); break;
		case Air_Ground: oppActData_AG.add(act); break;
		case Ground_Air: oppActData_GA.add(act); break;
		case Ground_Ground :oppActData_GG.add(act);break;
		}
	}
	
	private synchronized void setAirGroundAction(){
		this.G_Act = new LinkedList<Action>();
		this.A_Act = new LinkedList<Action>();
		
		G_Act.add(Action.CROUCH_GUARD);
		G_Act.add(Action.CROUCH_FA);
		G_Act.add(Action.CROUCH_FB);
		G_Act.add(Action.STAND_FA);
		G_Act.add(Action.CROUCH_A);
		G_Act.add(Action.CROUCH_B);
		G_Act.add(Action.STAND_A);
		G_Act.add(Action.STAND_B);
		G_Act.add(Action.THROW_B);
		G_Act.add(Action.THROW_A);
		G_Act.add(Action.FOR_JUMP);
		G_Act.add(Action.JUMP);
		G_Act.add(Action.BACK_STEP);
		G_Act.add(Action.STAND_D_DF_FA);
		G_Act.add(Action.STAND_D_DF_FB);
		G_Act.add(Action.STAND_F_D_DFA);
		G_Act.add(Action.STAND_F_D_DFB);
		G_Act.add(Action.STAND_D_DB_BA);
		G_Act.add(Action.STAND_D_DB_BB);
		G_Act.add(Action.STAND_D_DF_FC);

		A_Act.add(Action.AIR_GUARD);
		A_Act.add(Action.AIR_A);
		A_Act.add(Action.AIR_DA);
		A_Act.add(Action.AIR_FA);
		A_Act.add(Action.AIR_UA);
		A_Act.add(Action.AIR_D_DF_FA);
		A_Act.add(Action.AIR_F_D_DFA);
		A_Act.add(Action.AIR_D_DB_BA);	
		
	}
	
	@Override
	public String getCharacter() {
		return CHARACTER_ZEN;
	}
}
