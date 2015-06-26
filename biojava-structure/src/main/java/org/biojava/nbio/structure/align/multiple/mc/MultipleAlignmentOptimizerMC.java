package org.biojava.nbio.structure.align.multiple.mc;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.align.multiple.Block;
import org.biojava.nbio.structure.align.multiple.BlockSet;
import org.biojava.nbio.structure.align.multiple.MultipleAlignment;
import org.biojava.nbio.structure.align.multiple.MultipleAlignmentScorer;
import org.biojava.nbio.structure.align.multiple.MultipleAlignmentTools;
import org.biojava.nbio.structure.align.multiple.MultipleSuperimposer;
import org.biojava.nbio.structure.align.multiple.ReferenceSuperimposer;
import org.biojava.nbio.structure.jama.Matrix;

/**
 * This class takes a MultipleAlignment seed previously generated and runs a Monte Carlo optimization
 * in order to improve the overall score and highlight common structural motifs.
 * <p>
 * The seed alignment can be flexible, non-topological or include CP, but this optimization will not
 * change the number of flexible parts {@link BlockSet}s or non-topological regions {@link Block}. 
 * Thus, the definition of those parts depend exclusively on the pairwise alignment (or user alignment) 
 * used to generate the seed multiple alignment.
 * <p>
 * This class implements Callable, because multiple instances of the optimization can be run in parallel.
 * 
 * @author Aleix Lafita
 *
 */
public class MultipleAlignmentOptimizerMC implements Callable<MultipleAlignment> {
	
	private static final boolean debug = true;  //Prints the optimization moves and saves a file of the history
	private Random rnd;
	private MultipleSuperimposer imposer;
	
	//Optimization parameters
	private int Rmin;   	//Minimum number of aligned structures without a gap (33% of initial)
	private int Lmin;   	//Minimum alignment length of a Block
	private int convergenceSteps; //Steps without a score change before stopping the optimization
	private double C; //Probability function constant (probability of acceptance for bad moves)
	
	//Score function parameters - they are defined by the user
	private double Gopen; //Penalty for opening gap
	private double Gextend; //Penalty for extending gaps
	
	//Alignment Information
	private int size; 				//number of structures in the alignment
	private int blockNr;			//the number of Blocks corresponding to non-sequential aligned regions
	
	//Multiple Alignment Residues
	private MultipleAlignment msa;  //Alignment to optimize
	private List<SortedSet<Integer>> freePool; 	//List to store the residues not aligned. Dimensions are: [size][residues in the pool]
	
	//Score information
	private double mcScore;  // Optimization score, objective function
	
	//Original atom arrays of all the structures
	private List<Atom[]> atomArrays;
	private List<Integer> structureLengths;
	
	//Variables that store the history of the optimization, in order to be able to plot the evolution of the system.
	private List<Integer> lengthHistory;
	private List<Double> rmsdHistory;
	private List<Double> scoreHistory;
	
	/**
	 * Constructor.
	 * @param seedAln MultipleAlignment to be optimized.
	 * @param params the parameter beam
	 * @param reference the index of the most similar structure to all others
	 * @throws StructureException  
	 */
	public MultipleAlignmentOptimizerMC(MultipleAlignment seedAln, MultipleMcParameters params, int reference) throws StructureException {
		rnd = new Random(params.getRandomSeed());
		Gopen = params.getGapOpen();
		Gextend = params.getGapExtension();
		imposer = new ReferenceSuperimposer(reference);
		initialize(seedAln);
		if (params.getConvergenceSteps() == 0) convergenceSteps = Collections.min(structureLengths)*size;
		else convergenceSteps = params.getConvergenceSteps();
		if (params.getMinAlignedStructures() == 0) Rmin = Math.max(size/3, 2);
		else Rmin = Math.min(Math.max(params.getMinAlignedStructures(),2),size);  //has to be in the range [2-size]
		Lmin = params.getMinBlockLen();
		C = 10*size;
	}

	@Override
	public MultipleAlignment call() throws Exception {
		//The maximum number of iterations depends on the maximum possible alignment length and the number of structures
		optimizeMC(convergenceSteps*100);
		try {
			if (debug) saveHistory("/scratch/cemc/CeMcOptimizationHistory.csv");
		} catch(FileNotFoundException e) {}
		
		return msa;
	}
	
	/**
	 * Initialize all the variables of the optimization.
	 * @param seed MultipleAlignment starting point.
	 * @throws StructureException
	 * @throws StructureAlignmentException 
	 */
	private void initialize(MultipleAlignment seed) throws StructureException {
		
		//Initialize member variables
		msa = seed.clone();
		size = seed.size();
		Rmin = Math.max(size/3,2);
		atomArrays = msa.getEnsemble().getAtomArrays();
		structureLengths = new ArrayList<Integer>();
		for (int i=0; i<size; i++) structureLengths.add(atomArrays.get(i).length);
		
		//Initialize alignment variables
		blockNr = msa.getBlocks().size();
		freePool = new ArrayList<SortedSet<Integer>>();
		List<List<Integer>> aligned = new ArrayList<List<Integer>>();
		
		//Generate freePool residues from the ones not aligned
		for (int i=0; i<size; i++){
			List<Integer> residues = new ArrayList<Integer>();
			for (BlockSet bs : msa.getBlockSets()){
				for (Block b : bs.getBlocks()){
					for (int l=0; l<b.length(); l++){
						Integer residue = b.getAlignRes().get(i).get(l);
						if (residue != null) residues.add(residue);
					}
				}
			}
			aligned.add(residues);
			freePool.add(new TreeSet<Integer>());
		}
		
		//Add any residue not aligned to the free pool for every structure
		for (int i=0; i<size; i++){
			for (int k=0; k<atomArrays.get(i).length; k++){
				if (!aligned.get(i).contains(k)) freePool.get(i).add(k);
			}
		}
		
		checkGaps(); //Shrink columns not consistent with the Rmin parameter
		
		//Update the CEMC score for the seed aligment
		msa.clear();
		imposer.superimpose(msa);
		mcScore = MultipleAlignmentScorer.getMultipleMCScore(msa, Gopen, Gextend);
	}
	
	/**
	 *  Optimization method based in a Monte-Carlo approach. Starting from the refined alignment uses 4 types of moves:
	 *  <p>
	 *  	1- Shift Row: if there are enough freePool residues available.<p>
	 *  	2- Expand Block: add another alignment column if there are residues available.<p>
	 *  	3- Shrink Block: move a block column to the freePool.<p>
	 *  	4- Insert gap: insert a gap in a random position of the alignment.
	 *  
	 */
	private void optimizeMC(int maxIter) throws StructureException {
		
		//Initialize the history variables
		lengthHistory = new ArrayList<Integer>();
		rmsdHistory = new ArrayList<Double>();
		scoreHistory = new ArrayList<Double>();
		
		int conv = 0;  //Number of steps without an alignment improvement
		int stepsToConverge = Math.max(maxIter/50,1000);
		int i = 1;
		
		while (i<maxIter && conv<stepsToConverge){
			
			//Save the state of the system in case the modifications are not favorable
			MultipleAlignment lastMSA = msa.clone();
			List<SortedSet<Integer>> lastFreePool = new ArrayList<SortedSet<Integer>>();
			for (int k=0; k<size; k++){
				SortedSet<Integer> p = new TreeSet<Integer>();
				for (Integer l:freePool.get(k)) p.add(l);
				lastFreePool.add(p);
			}
			double lastScore = mcScore;
			
			boolean moved = false;
			
			while (!moved){
				//Randomly select one of the steps to modify the alignment. 
				//Because two moves are biased, the probabilities are not the same
				double move = rnd.nextDouble();
				if (move < 0.5){
						moved = shiftRow();
						if (debug) System.out.println("did shift");
				}
				else if (move < 0.8){
						moved = expandBlock();
						if (debug) System.out.println("did expand");
				}
				else if (move < 0.9){
						moved = shrinkBlock();
						if (debug) System.out.println("did shrink");
				}
				else {
						moved = insertGap();
						if (debug) System.out.println("did insert gap");
				}
			}
			
			//Get the score of the new alignment
			msa.clear();
			imposer.superimpose(msa);
			mcScore = MultipleAlignmentScorer.getMultipleMCScore(msa, Gopen, Gextend);
			
			double AS = mcScore-lastScore;  //Change in the optimization Score
			double prob=1.0;
			
			if (AS<0){
				
				//Probability of accepting the new alignment given that produces a negative score change
				prob = probabilityFunction(AS,i,maxIter);
				double p = rnd.nextDouble();
				//Reject the move
				if (p>prob){
					msa = lastMSA;
					freePool = lastFreePool;
					mcScore = lastScore;
					conv ++; //Increment the number of steps without a change in score
					
				} else conv = 0;
				
			} else conv=0;
			
			if (debug) 	System.out.println("Step: "+i+": --prob: "+prob+", --score: "+AS+", --conv: "+conv);
			
			if (i%100==1){
				lengthHistory.add(msa.length());
				rmsdHistory.add(MultipleAlignmentScorer.getRMSD(msa));
				scoreHistory.add(mcScore);
			}
			
			i++;
		}
		
		//Return Multiple Alignment
		imposer.superimpose(msa);
		MultipleAlignmentScorer.calculateScores(msa);
		msa.putScore(MultipleAlignmentScorer.MC_SCORE, mcScore);
	}

	/**
	 * Method that loops through all the alignment columns and checks that there are no more gaps than the 
	 * maximum allowed, Rmin.<p>
	 * There must be at least Rmin residues different than null in every alignment column.
	 * In case there is a column with more gaps than allowed it will be shrinked (moved to freePool).
	 * 
	 * @return true if any columns has been shrinked and false otherwise
	 */
	private boolean checkGaps(){
		
		boolean shrinkedAny = false;
		
		List<List<Integer>> shrinkColumns = new ArrayList<List<Integer>>();
		//Loop for each Block
		for (Block b:msa.getBlocks()){
			List<Integer> shrinkCol = new ArrayList<Integer>();
			//Loop for each column in the Block
			for (int res=0; res<b.length(); res++){
				int gapCount = 0;
				//Loop for each structure and Block and count the gaps in the column
				for (int su=0; su<size; su++){
					if (b.getAlignRes().get(su).get(res) == null) gapCount++;
				}
				if ((size-gapCount)<Rmin){
					//Add the column to the shrink list
					shrinkCol.add(res);
				}
			}
			shrinkColumns.add(shrinkCol);
		}
		//Shrink the columns that have more gaps than allowed (from higher indicies to lower ones, to not interfere)
		for (int b=0; b<blockNr; b++){
			for (int col=shrinkColumns.get(b).size()-1; col>=0; col--){
				for (int str=0; str<size; str++){
					Integer residue = msa.getBlocks().get(b).getAlignRes().get(str).get(shrinkColumns.get(b).get(col));
					msa.getBlocks().get(b).getAlignRes().get(str).remove((int) shrinkColumns.get(b).get(col));
					if (residue != null) freePool.get(str).add(residue);
				}
				shrinkedAny = true;
			}
		}
		return shrinkedAny;
	}
	
	/**
	 * Insert a gap in one of the structures in a random position in the alignment.
	 * The random distribution is not uniform, positions with higher distance are more likely
	 * to be gapped.
	 * A gap is a null in the Block position.
	 */
	private boolean insertGap() {
		
		//Select residue by maximum distance
		Matrix residueDistances = MultipleAlignmentTools.getAverageResidueDistances(msa);
		double maxDist = Double.MIN_VALUE;
		int structure = 0;
		int block = 0;
		int position = 0;
		int column = 0;
		for (int b=0; b<blockNr; b++){
			for (int col=0; col<msa.getBlocks().get(b).length(); col++){
				for (int str=0; str<size; str++){
					if (residueDistances.get(str, column) != -1){
						if (residueDistances.get(str, column) > maxDist){
							if (rnd.nextDouble() > 0.5) {   //Introduce some randomness in the choice
								structure = str;
								block = b;
								position = col; 
							}
						}
					}
				}
				column++;
			}
		}
		//Let gaps insertion only if the subunit is larger than the minimum length
		if (msa.getBlocks().get(block).length() <= Lmin) return false;
			
		//Insert the gap at the position
		Integer residueL = msa.getBlocks().get(block).getAlignRes().get(structure).get(position);
		if (residueL != null) freePool.get(structure).add(residueL);
		else return false;  //If there was a gap already in the position.
		
		msa.getBlocks().get(block).getAlignRes().get(structure).set(position,null);
		checkGaps();
		return true;
	}

	/**
	 *  Move all the block residues of one subunit one position to the left or right and 
	 *  move the corresponding boundary residues from the freePool to the block, and viceversa. 
	 *  <p>
	 *  The boundaries are determined by any irregularity (either a gap, boundary or a 
	 *  discontinuity in the alignment).
	 */
	private boolean shiftRow(){

		int str = rnd.nextInt(size); //Select randomly the subunit that is going to be shifted
		int rl = rnd.nextInt(2);  //Select between moving right (0) or left (1)
		int bk = rnd.nextInt(blockNr); //Select randomly the Block if more than 1
		int res = rnd.nextInt(msa.getBlocks().get(bk).length()); //Residue as a pivot to make the shift
		
		Block currentBlock = msa.getBlocks().get(bk);
		
	//When the pivot residue is null try to add a residue from the freePool
		if (currentBlock.getAlignRes().get(str).get(res) == null){
			//Residues not null at the right and left of the pivot null residue
			int rightRes = res;
			int leftRes = res;
			//Find the boundary to the right abd left (a residue different than null)
			while (currentBlock.getAlignRes().get(str).get(rightRes) == null && rightRes<currentBlock.length()-1) rightRes++;
			while (currentBlock.getAlignRes().get(str).get(leftRes) == null && leftRes>0) leftRes--;
			
			//If they both are null return because it means that the whole block is null
			if (currentBlock.getAlignRes().get(str).get(leftRes) == null && currentBlock.getAlignRes().get(str).get(rightRes) == null) return false;
			else if (currentBlock.getAlignRes().get(str).get(leftRes) == null){
				//Choose the sequentially previous residue of the known one
				Integer residue = currentBlock.getAlignRes().get(str).get(rightRes)-1;
				if (freePool.get(str).contains(residue)) {
					currentBlock.getAlignRes().get(str).set(res,residue);
					freePool.get(str).remove(residue);
				} else return false;
			} 
			else if (currentBlock.getAlignRes().get(str).get(rightRes) == null){
				//Choose the sequentially next residue of the known one
				Integer residue = currentBlock.getAlignRes().get(str).get(leftRes)+1;
				if (freePool.contains(residue)) {
					currentBlock.getAlignRes().get(str).set(res,residue);
					freePool.get(str).remove(residue);
				} else return false;
			} 
			else { 
				//If the boundaries are consecutive residues no residue can be added
				if (currentBlock.getAlignRes().get(str).get(rightRes) == currentBlock.getAlignRes().get(str).get(leftRes)+1) return false;
				else{
					//Choose randomly a residue in between left and right
					Integer residue = rnd.nextInt(currentBlock.getAlignRes().get(str).get(rightRes)-currentBlock.getAlignRes().get(str).get(leftRes)-1) + currentBlock.getAlignRes().get(str).get(leftRes)+1;
					if (freePool.get(str).contains(residue)) {
						currentBlock.getAlignRes().get(str).set(res,residue);
						freePool.get(str).remove(residue);
					} else throw new IllegalStateException("The freePool does not contain a residue in between two non-consecutive aligned positions");
				}
			}
			return true;
		}
		
	//When the residue is different than null try to shift the whole block of consecutive (without any gaps) residues
		switch(rl){
		case 0: //Move to the right
			
			int leftBoundary = res-1;  //Find the nearest boundary to the left of the pivot
			int leftPrevRes = res;
			while (true){
				if(leftBoundary < 0) break;  //Break if the the left boundary has been found to be the start of the block (=-1)
				else {
					if (currentBlock.getAlignRes().get(str).get(leftBoundary) == null) break; //Break if there is a gap (this is the boundary)
					else if (currentBlock.getAlignRes().get(str).get(leftPrevRes) > currentBlock.getAlignRes().get(str).get(leftBoundary)+1) break;  //Break if there is a discontinuity
				}
				leftPrevRes = leftBoundary;
				leftBoundary--;
			}
			leftBoundary++;
			
			int rightBoundary = res+1;  //Find the nearest boundary to the right of the pivot
			int rightPrevRes = res;
			while (true){
				if(rightBoundary == currentBlock.length()) break;  //Break if the the right boundary has been found (=subunitLen)
				else {
					if (currentBlock.getAlignRes().get(str).get(rightBoundary) == null) break;  //Break if there is a gap
					else if (currentBlock.getAlignRes().get(str).get(rightPrevRes)+1 < currentBlock.getAlignRes().get(str).get(rightBoundary)) break;  //Discontinuity
				}
				rightPrevRes = rightBoundary;
				rightBoundary++;
			}
			rightBoundary--;
			
			//Residues at the boundary
			Integer residueR0 = currentBlock.getAlignRes().get(str).get(rightBoundary);
			Integer residueL0 = currentBlock.getAlignRes().get(str).get(leftBoundary);
			
			//Remove the residue at the right of the block and add it to the freePool
			currentBlock.getAlignRes().get(str).remove(rightBoundary);
			if (residueR0 != null) freePool.get(str).add(residueR0);
			else throw new IllegalStateException("The residue right boundary in shift is null! Cannot be...");
			
			//Add the residue at the left of the block from the freePool to the block
			if (residueL0 != null) residueL0 -= 1;
			else throw new IllegalStateException("The residue left boundary in shift is null! Cannot be...");
			if (freePool.get(str).contains(residueL0)){
				currentBlock.getAlignRes().get(str).add(leftBoundary,residueL0);
				freePool.get(str).remove(residueL0);
			} else currentBlock.getAlignRes().get(str).add(leftBoundary,null);
			
			break;
			
		case 1: //Move to the left
			
			int leftBoundary1 = res-1;  //Find the nearest boundary to the left of the pivot
			int leftPrevRes1 = res;
			while (true){
				if(leftBoundary1 < 0) break;  //Break if the the left boundary has been found to be the start of the block (=-1)
				else {
					if (currentBlock.getAlignRes().get(str).get(leftBoundary1) == null) break; //Break if there is a gap (this is the boundary)
					else if (currentBlock.getAlignRes().get(str).get(leftPrevRes1) > currentBlock.getAlignRes().get(str).get(leftBoundary1)+1) break;  //Break if there is a discontinuity
				}
				leftPrevRes1 = leftBoundary1;
				leftBoundary1--;
			}
			leftBoundary1++;
			
			int rightBoundary1 = res+1;  //Find the nearest boundary to the right of the pivot
			int rightPrevRes1 = res;
			while (true){
				if(rightBoundary1 == currentBlock.length()) break;  //Break if the the right boundary has been found (=subunitLen)
				else {
					if (currentBlock.getAlignRes().get(str).get(rightBoundary1) == null) break;  //Break if there is a gap
					else if (currentBlock.getAlignRes().get(str).get(rightPrevRes1)+1 < currentBlock.getAlignRes().get(str).get(rightBoundary1)) break;  //Discontinuity
				}
				rightPrevRes1 = rightBoundary1;
				rightBoundary1++;
			}
			rightBoundary1--;
			
			//Residues at the boundary
			Integer residueR1 = currentBlock.getAlignRes().get(str).get(rightBoundary1);
			Integer residueL1 = currentBlock.getAlignRes().get(str).get(leftBoundary1);
			
			//Add the residue at the right of the block from the freePool to the block
			if (residueR1 != null) residueR1 += 1;
			else throw new IllegalStateException("The residue right boundary in shift is null! Cannot be...");
			if (freePool.contains(residueR1)){
				if (rightBoundary1==currentBlock.length()-1) currentBlock.getAlignRes().get(str).add(residueR1);
				else currentBlock.getAlignRes().get(str).add(rightBoundary1+1,residueR1);
				freePool.get(str).remove(residueR1);
			} else currentBlock.getAlignRes().get(str).add(rightBoundary1+1,null);
			
			//Remove the residue at the left of the block and add it to the freePool
			currentBlock.getAlignRes().get(str).remove(leftBoundary1);
			if (residueL1 != null) freePool.get(str).add(residueL1);
			else throw new IllegalStateException("The residue left boundary in shift is null! Cannot be...");
			
			break;
		}
		checkGaps();
		return true;
	}
	
	/**
	 *  It extends the alignment one position to the right or to the left of a randomly selected position
	 *  by moving the consecutive residues of each subunit (if enough) from the freePool to the block.
	 *  <p>
	 *  If there are not enough residues in the freePool it introduces gaps.
	 */
	private boolean expandBlock(){
			
		int rl = rnd.nextInt(2);  //Select between expanding right (0) or left (1)
		int bk = rnd.nextInt(blockNr); //Select randomly the Block if more than 1
		int res = rnd.nextInt(msa.getBlocks().get(bk).length()); //Residue as a pivot to expand the subunits
		
		Block currentBlock = msa.getBlocks().get(bk);
		int gaps = 0; //store the number of gaps in the expansion
		
		switch (rl) {
		case 0:
			
			int rightBoundary = res;
			int[] previousPos = new int[size];
			for (int str=0; str<size; str++) previousPos[str] = -1;
			
			//Search a position to the right that has at minimum Rmin non consecutive residues (otherwise not enough freePool residues to expand)
			while (currentBlock.length()-1>rightBoundary){
				int noncontinuous = 0;
				for (int str=0; str<size; str++){
					if (currentBlock.getAlignRes().get(str).get(rightBoundary) == null) continue;
					else if (previousPos[str] == -1) previousPos[str] = currentBlock.getAlignRes().get(str).get(rightBoundary);
					else if (currentBlock.getAlignRes().get(str).get(rightBoundary) > previousPos[str]+1) noncontinuous++;
				}
				if (noncontinuous < Rmin) rightBoundary++;
				else break;
			}
			if (rightBoundary > 0) rightBoundary--;
			
			//Expand the block with the residues at the subunit boundaries
			for (int str=0; str<size; str++){
				Integer residueR = currentBlock.getAlignRes().get(str).get(rightBoundary);
				if (residueR == null){
					if (rightBoundary == currentBlock.length()-1) currentBlock.getAlignRes().get(str).add(null); 
					else currentBlock.getAlignRes().get(str).add(rightBoundary+1,null);
					gaps++;
				} else if (freePool.get(str).contains(residueR+1)){
					Integer residueAdd = residueR+1;
					if (rightBoundary == currentBlock.length()-1) currentBlock.getAlignRes().get(str).add(residueAdd); 
					else currentBlock.getAlignRes().get(str).add(rightBoundary+1,residueAdd);
					freePool.get(str).remove(residueAdd);
				} else {
					if (rightBoundary == currentBlock.length()-1) currentBlock.getAlignRes().get(str).add(null); 
					else currentBlock.getAlignRes().get(str).add(rightBoundary+1,null);
					gaps++;
				}
			}
			break;
			
		case 1:
			
			int leftBoundary = res;
			int[] nextPos = new int[size];
			for (int str=0; str<size; str++) nextPos[str] = -1;
			
			//Search a position to the right that has at minimum Rmin non consecutive residues (otherwise not enough freePool residues to expand)
			while (leftBoundary>0){
				int noncontinuous = 0;
				for (int str=0; str<size; str++){
					if (currentBlock.getAlignRes().get(str).get(leftBoundary) == null) continue;
					else if (nextPos[str] == -1) nextPos[str] = currentBlock.getAlignRes().get(str).get(leftBoundary);
					else if (currentBlock.getAlignRes().get(str).get(leftBoundary) < nextPos[str]-1) noncontinuous++;
				}
				if (noncontinuous < Rmin) leftBoundary--;
				else break;
			}
			
			//Expand the block with the residues at the subunit boundaries
			for (int str=0; str<size; str++){
				Integer residueL = currentBlock.getAlignRes().get(str).get(leftBoundary);
				if (residueL == null) {
					currentBlock.getAlignRes().get(str).add(leftBoundary,null);
					gaps++;
				} else if (freePool.get(str).contains(residueL-1)){
					Integer residueAdd = residueL-1;
					currentBlock.getAlignRes().get(str).add(leftBoundary,residueAdd);
					freePool.get(str).remove(residueAdd);
				} else {
					currentBlock.getAlignRes().get(str).add(leftBoundary, null);
					gaps++;
				}
			}
			break;
		}
		if (size-gaps >= Rmin) return true;
		else checkGaps();  //TODO more efficient would be to shrink directly the known column
		return false;
	}
	
	/**
	 * Deletes an alignment column at a randomly selected position.
	 */
	private boolean shrinkBlock(){
		
		//Select column by maximum distance
		Matrix residueDistances = MultipleAlignmentTools.getAverageResidueDistances(msa);
		double[] colDistances = new double[msa.length()];
		double maxDist = Double.MIN_VALUE;
		int position = 0;
		int block = 0;
		int column = 0;
		for (int b=0; b<msa.getBlocks().size(); b++){
			for (int col=0; col<msa.getBlocks().get(b).length(); col++){
				int normalize = 0;
				for (int s=0; s<size; s++){
					if (residueDistances.get(s, column) != -1){
						colDistances[column] += residueDistances.get(s, column);
						normalize++;
					}
				}
				colDistances[column] /= normalize;
				if (colDistances[column] > maxDist){
					if (rnd.nextDouble() > 0.5) {  //Introduce some randomness in the choice
						maxDist = colDistances[column];
						position = col;
						block = b;
					}
				}
				column++;
			}
		}
		//Let shrink moves only if the subunit is larger than the minimum length
		if (msa.getBlocks().get(block).length() <= Lmin) return false;
		Block currentBlock = msa.getBlocks().get(block);
		
		for (int str=0; str<size; str++){
			Integer residue = currentBlock.getAlignRes().get(str).get(position);
			currentBlock.getAlignRes().get(str).remove(position);
			if (residue != null) freePool.get(str).add(residue);
		}
		return true;
	}
	
	/**
	 *  Calculates the probability of accepting a bad move given the iteration step and the score change.
	 *  
	 *  Function: p=(C-AS)/(m*C)   *slightly different from the CEMC algorithm.
	 *  Added a normalization factor so that the probability approaches 0 when the maxIter is reached.
	 */
	private double probabilityFunction(double AS, int m, int maxIter) {
		
		double prob = (C+AS)/(m*C);
		double norm = (1-(m*1.0)/maxIter);  //Normalization factor
		return Math.min(Math.max(prob*norm,0.0),1.0);
	}
	
	/**
	 * Save the evolution of the optimization process as a csv file.
	 */
	private void saveHistory(String filePath) throws IOException{
		
	    FileWriter writer = new FileWriter(filePath);
	    writer.append("Step,Length,RMSD,Score\n");
	    
	    for (int i=0; i<lengthHistory.size(); i++){
	    	writer.append(i*100+","+lengthHistory.get(i)+","+rmsdHistory.get(i)+","+scoreHistory.get(i)+"\n");
	    }
	    writer.flush();
	    writer.close();
	}
}
