package id.ac.its.soka;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MetaheuristicSchedulingSimulation {
    private static final Path WORKLOAD = Path.of("data/workload.csv");
    private static final Path RESULTS = Path.of("results");
    private static final int MIN_ROWS = 45_000;
    private static final int[] WORKLOAD_SIZES = {1_000,2_000,3_000,4_000,5_000};
    private static final int REPEATS = 3;
    private static final String[] ALGORITHMS = {"SMA", "AO"};
    private static final int POPULATION_SIZE = 8;
    private static final int ITERATIONS = 12;
    private static final double TIME_WEIGHT = 0.60;
    private static final double COST_WEIGHT = 0.40;

    private record Task(long id,double arrival,long length,int pes,int ramMb,double deadline) {}
    private record VmSpec(int id,String type,int pes,double mips,int ramMb,double cost,int dcId,int hostId) {}
    private record Assignment(Task task,VmSpec vm,double start,double finish) {}
    private record Evaluation(int[] assignment,double score) {}
    private record RunResult(int dryRun,int repeat,int startRow,int endRow,int workloadSize,String algorithm,
                             double makespan,double totalCost,double cpuUtilization,double throughput,
                             double imbalance,double deadlineMissRatio,double budget,boolean budgetExceeded) {}

    public static void main(String[] args) throws Exception {
        Set<String> selected = selectedAlgorithms(args);
        boolean printOutput = Arrays.asList(args).contains("--print-output");
        Files.createDirectories(RESULTS);
        List<Task> source = loadWorkload();
        writeConfig(source.size());
        List<RunResult> results = new ArrayList<>();
        int cursor = 0;
        for (int dryRun = 1; dryRun <= WORKLOAD_SIZES.length; dryRun++) {
            int size = WORKLOAD_SIZES[dryRun - 1];
            for (int repeat = 1; repeat <= REPEATS; repeat++) {
                int start = cursor;
                int end = cursor + size;
                List<Task> chunk = source.subList(start, end);
                cursor = end;
                for (String algorithm : ALGORITHMS) {
                    if (!selected.contains("ALL") && !selected.contains(algorithm)) continue;
                    results.add(run(chunk,dryRun,repeat,start + 1,end,algorithm,printOutput && dryRun == 1 && repeat == 1));
                }
            }
        }
        if (cursor != 45_000) throw new IllegalStateException("Unexpected workload cursor: " + cursor);
        writeRuns(results);
        writeSummary(results);
        System.out.printf(Locale.US,"Completed %d simulation runs across %d unique workload rows.%n",results.size(),cursor);
        System.out.printf("Results: %s%n",RESULTS.toAbsolutePath());
    }

    private static Set<String> selectedAlgorithms(String[] args) {
        String value = Arrays.stream(args).filter(a -> a.startsWith("--algorithm="))
            .map(a -> a.substring("--algorithm=".length()).trim().toUpperCase(Locale.ROOT))
            .findFirst().orElse("ALL");
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(value.split(",")));
        if (selected.contains("ALL") && selected.size() > 1) throw new IllegalArgumentException("ALL cannot be combined with SMA or AO.");
        if (!selected.stream().allMatch(a -> a.equals("ALL") || Arrays.asList(ALGORITHMS).contains(a))) throw new IllegalArgumentException("Use ALL, SMA, AO, or SMA,AO.");
        return selected;
    }

    private static List<Task> loadWorkload() throws IOException {
        if (!Files.exists(WORKLOAD)) throw new FileNotFoundException("Missing " + WORKLOAD + ". Provide at least 45000 unique rows.");
        List<Task> tasks = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(WORKLOAD)) {
            String line; boolean header = true; int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                if (header) { header = false; if (line.toLowerCase(Locale.ROOT).startsWith("task_id")) continue; }
                String[] c = line.split(",",-1);
                if (c.length < 5) throw new IllegalArgumentException("Invalid workload row at line " + lineNumber);
                long id = Long.parseLong(c[0].trim());
                double arrival = Double.parseDouble(c[1].trim());
                double duration = Double.parseDouble(c[2].trim());
                double cpu = Double.parseDouble(c[3].trim());
                double memory = Double.parseDouble(c[4].trim());
                String fingerprint = String.join(",",Arrays.stream(c).limit(5).map(String::trim).toList());
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate task_id " + id + " at line " + lineNumber);
                if (!fingerprints.add(fingerprint)) throw new IllegalArgumentException("Duplicate workload row at line " + lineNumber);
                if (duration <= 0 || cpu <= 0 || memory <= 0) throw new IllegalArgumentException("Invalid workload values at line " + lineNumber);
                long length = Math.max(1L,Math.round(duration * 1000.0));
                int pes = cpu <= 1 ? 1 : cpu <= 2 ? 2 : 4;
                int ram = memory <= 512 ? 512 : memory <= 1024 ? 1024 : memory <= 2048 ? 2048 : 4096;
                tasks.add(new Task(id,Math.max(0,arrival),length,pes,ram,0));
            }
        }
        if (tasks.size() < MIN_ROWS) throw new IllegalArgumentException("Need at least " + MIN_ROWS + " unique valid workload rows; found " + tasks.size());
        double first = tasks.stream().mapToDouble(Task::arrival).min().orElse(0);
        return tasks.stream().map(t -> new Task(t.id,t.arrival - first,t.length,t.pes,t.ramMb,0)).toList();
    }

    private static List<VmSpec> vmSpecs() {
        return List.of(
            new VmSpec(0,"Small",1,1000,2048,0.010,1,0),new VmSpec(1,"Small",1,1000,2048,0.010,1,0),
            new VmSpec(2,"Medium",2,1800,4096,0.018,1,1),new VmSpec(3,"Large",4,2500,8192,0.032,1,1),
            new VmSpec(4,"Small",1,1000,2048,0.010,2,2),new VmSpec(5,"Small",1,1000,2048,0.010,2,2),
            new VmSpec(6,"Medium",2,1800,4096,0.018,2,3),new VmSpec(7,"Large",4,2500,8192,0.032,2,3));
    }

    private static RunResult run(List<Task> input,int dryRun,int repeat,int startRow,int endRow,String algorithm,boolean printOutput) {
        List<VmSpec> vms = vmSpecs();
        List<Task> tasks = input.stream().map(t -> new Task(t.id,t.arrival,t.length,t.pes,t.ramMb,t.arrival + 1.5 * t.length / 3600.0)).toList();
        int[] choice = optimize(tasks,vms,algorithm,1000L + startRow * 31L + algorithm.hashCode());
        Map<Long,Assignment> planned = assignments(tasks,vms,choice);
        CloudSimPlus simulation = new CloudSimPlus();
        Datacenter dc1 = createDatacenter(simulation,1), dc2 = createDatacenter(simulation,2);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        List<Vm> cloudVms = vms.stream().map(MetaheuristicSchedulingSimulation::createVm).toList();
        broker.setDatacenterMapper((dc,vm) -> vm.getId() < 4 ? dc1 : dc2);
        broker.submitVmList(cloudVms);
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (Task task : tasks) {
            CloudletSimple cloudlet = new CloudletSimple(task.length,task.pes);
            cloudlet.setId(task.id);
            cloudlet.setSubmissionDelay(task.arrival);
            cloudlet.setFileSize(300);
            cloudlet.setOutputSize(300);
            cloudlet.setUtilizationModelCpu(new org.cloudsimplus.utilizationmodels.UtilizationModelFull());
            cloudlet.setVm(cloudVms.get(planned.get(task.id).vm.id));
            cloudlets.add(cloudlet);
        }
        broker.submitCloudletList(cloudlets); simulation.start();
        List<Cloudlet> finished = broker.getCloudletFinishedList();
        double minArrival = tasks.stream().mapToDouble(Task::arrival).min().orElse(0);
        double lastFinish = finished.stream().mapToDouble(Cloudlet::getFinishTime).max().orElse(0);
        double makespan = Math.max(0,lastFinish - minArrival), totalCost = 0; int misses = 0;
        Map<Integer,Double> busy = new HashMap<>();
        for (Cloudlet c : finished) {
            VmSpec vm = vms.get((int)c.getVm().getId()); double cpuTime = Math.max(0,c.getActualCpuTime());
            totalCost += cpuTime * vm.cost; busy.merge(vm.id,cpuTime,Double::sum);
            if (c.getFinishTime() > planned.get(c.getId()).task.deadline) misses++;
        }
        double totalBusy = busy.values().stream().mapToDouble(Double::doubleValue).sum();
        double utilization = makespan == 0 ? 0 : totalBusy / (makespan * vms.stream().mapToInt(VmSpec::pes).sum());
        double throughput = makespan == 0 ? 0 : finished.size() / makespan;
        double maxLoad = busy.values().stream().mapToDouble(Double::doubleValue).max().orElse(0), minLoad = busy.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double imbalance = maxLoad == 0 ? 0 : (maxLoad - minLoad) / maxLoad;
        double budget = theoreticalCost(tasks,vms) * 1.20;
        if (printOutput) printOutput(finished,vms,algorithm,makespan,dryRun,repeat);
        return new RunResult(dryRun,repeat,startRow,endRow,tasks.size(),algorithm,makespan,totalCost,utilization,throughput,imbalance,tasks.isEmpty() ? 0 : (double)misses / tasks.size(),budget,totalCost > budget);
    }

    private static int[] optimize(List<Task> tasks,List<VmSpec> vms,String algorithm,long seed) {
        Random random = new Random(seed);
        double scaleTime = tasks.stream().mapToDouble(t -> vms.stream().filter(v -> feasible(t,v)).mapToDouble(v -> runtime(t,v)).min().orElse(1)).sum();
        double scaleCost = theoreticalCost(tasks,vms); List<int[]> population = new ArrayList<>();
        for (int p = 0; p < POPULATION_SIZE; p++) { int[] candidate = new int[tasks.size()]; for (int i = 0; i < candidate.length; i++) candidate[i] = random.nextInt(vms.size()); population.add(repair(tasks,vms,candidate)); }
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            population.sort(Comparator.comparingDouble(c -> score(tasks,vms,c,scaleTime,scaleCost)));
            double bestFitness = score(tasks,vms,population.get(0),scaleTime,scaleCost);
            double worstFitness = score(tasks,vms,population.get(population.size() - 1),scaleTime,scaleCost);
            int[] best = population.get(0).clone(); List<int[]> next = new ArrayList<>(); next.add(best);
            for (int p = 1; p < POPULATION_SIZE; p++) {
                int[] candidate = population.get(p).clone();
                if (algorithm.equals("SMA")) {
                    double currentFitness = score(tasks,vms,candidate,scaleTime,scaleCost);
                    updateSma(candidate,best,iteration,p,currentFitness,bestFitness,worstFitness,random,vms.size());
                } else updateAo(candidate,best,population,iteration,random,vms.size());
                next.add(repair(tasks,vms,candidate));
            }
            population = next;
        }
        return population.stream().min(Comparator.comparingDouble(c -> score(tasks,vms,c,scaleTime,scaleCost))).orElseThrow();
    }

    private static void updateSma(int[] candidate,int[] best,int iteration,int rank,
                                 double currentFitness,double bestFitness,double worstFitness,
                                 Random random,int vmCount) {
        // Discrete form of the SMA position update. The candidate rank controls
        // the signed weight: better candidates are attracted to the food source,
        // while weaker candidates retain more exploratory movement.
        double progress = (double) iteration / Math.max(1, ITERATIONS - 1);
        double fitnessRange = Math.max(1e-12, worstFitness - bestFitness);
        double relativeFitness = Math.max(0, Math.min(1, (currentFitness - bestFitness) / fitnessRange));
        double magnitude = 1.0 + random.nextDouble() * Math.log10(1.0 + relativeFitness * 9.0);
        double weight = rank < POPULATION_SIZE / 2 ? magnitude : -magnitude;
        double attraction = Math.max(0.05, Math.min(0.90, Math.abs(weight) * (1.0 - 0.65 * progress)));
        double z = 0.03 + 0.07 * progress;

        for (int i = 0; i < candidate.length; i++) {
            double r = random.nextDouble();
            if (r < z) {
                // Random exploration.
                candidate[i] = random.nextInt(vmCount);
            } else if (r < z + attraction) {
                // Move toward the best food source.
                candidate[i] = best[i];
            } else if (r < z + attraction + 0.20) {
                // Contract or make a small local adjustment.
                if (random.nextBoolean()) candidate[i] = best[i];
            }
            // Otherwise the position remains unchanged.
        }
    }

    private static void updateAo(int[] candidate,int[] best,List<int[]> population,
                                int iteration,Random random,int vmCount) {
        // Discrete AO adaptation. The first two strategies explore broadly for
        // the first two thirds of the search, then the last two strategies
        // refine assignments around the current best solution.
        int[] meanPosition = meanPosition(population,vmCount);
        boolean explorationPhase = iteration < (2.0 / 3.0) * ITERATIONS;
        double progress = (double) iteration / Math.max(1, ITERATIONS - 1);

        for (int i = 0; i < candidate.length; i++) {
            double rand = random.nextDouble();
            if (explorationPhase && rand <= 0.5) {
                // Strategy 1: high-soaring exploration. Compare the current
                // position with the mean position of the whole population.
                candidate[i] = random.nextBoolean() ? meanPosition[i] : random.nextInt(vmCount);
            } else if (explorationPhase) {
                // Strategy 2: contour flight. Move toward a potential target
                // between the population mean and the best known assignment.
                int target = meanPosition[i] + (int) Math.round(
                    random.nextDouble() * (best[i] - meanPosition[i]));
                candidate[i] = clampVm(target,vmCount);
            } else if (rand <= 0.5) {
                // Strategy 3: low-flight exploitation. Search around Xbest
                // with a neighborhood that shrinks as iterations progress.
                int radius = Math.max(1,(int) Math.ceil((1.0 - progress) * vmCount / 3.0));
                candidate[i] = clampVm(best[i] + random.nextInt(2 * radius + 1) - radius,vmCount);
            } else {
                // Strategy 4: walk-and-grab exploitation. Apply a small local
                // shift around Xbest, including the option to keep Xbest.
                int shift = random.nextInt(3) - 1;
                candidate[i] = clampVm(best[i] + shift,vmCount);
            }
        }
    }

    private static int[] meanPosition(List<int[]> population,int vmCount) {
        int length = population.get(0).length; int[] mean = new int[length];
        for (int i = 0; i < length; i++) {
            double total = 0;
            for (int[] candidate : population) total += candidate[i];
            mean[i] = clampVm((int) Math.round(total / population.size()),vmCount);
        }
        return mean;
    }

    private static int clampVm(int id,int vmCount) {
        return Math.max(0,Math.min(vmCount - 1,id));
    }

    private static int[] repair(List<Task> tasks,List<VmSpec> vms,int[] candidate) {
        int[] repaired = candidate.clone();
        for (int i = 0; i < repaired.length; i++) { int id = Math.max(0,Math.min(vms.size() - 1,repaired[i])); if (!feasible(tasks.get(i),vms.get(id))) repaired[i] = cheapestFeasible(tasks.get(i),vms).id; }
        return repaired;
    }

    private static double score(List<Task> tasks,List<VmSpec> vms,int[] assignment,double scaleTime,double scaleCost) {
        Map<Integer,Double> available = new HashMap<>(); for (VmSpec vm : vms) available.put(vm.id,0.0);
        double makespan = 0,cost = 0;
        for (int i = 0; i < tasks.size(); i++) { Task task = tasks.get(i); VmSpec vm = vms.get(assignment[i]); double start = Math.max(task.arrival,available.get(vm.id)); double finish = start + runtime(task,vm); available.put(vm.id,finish); makespan = Math.max(makespan,finish); cost += runtime(task,vm) * vm.cost; }
        return TIME_WEIGHT * makespan / Math.max(1,scaleTime) + COST_WEIGHT * cost / Math.max(1,scaleCost);
    }

    private static Map<Long,Assignment> assignments(List<Task> tasks,List<VmSpec> vms,int[] choice) {
        Map<Integer,Double> available = new HashMap<>(); for (VmSpec vm : vms) available.put(vm.id,0.0); Map<Long,Assignment> result = new HashMap<>();
        for (int i = 0; i < tasks.size(); i++) { Task task = tasks.get(i); VmSpec vm = vms.get(choice[i]); double start = Math.max(task.arrival,available.get(vm.id)); double finish = start + runtime(task,vm); available.put(vm.id,finish); result.put(task.id,new Assignment(task,vm,start,finish)); }
        return result;
    }

    private static boolean feasible(Task task,VmSpec vm) { return vm.pes >= task.pes && vm.ramMb >= task.ramMb; }
    private static VmSpec cheapestFeasible(Task task,List<VmSpec> vms) { return vms.stream().filter(v -> feasible(task,v)).min(Comparator.comparingDouble(v -> runtime(task,v) * v.cost)).orElseThrow(); }
    private static double runtime(Task task,VmSpec vm) { return task.length / (vm.pes * vm.mips); }
    private static double theoreticalCost(List<Task> tasks,List<VmSpec> vms) { return tasks.stream().mapToDouble(t -> { VmSpec vm = cheapestFeasible(t,vms); return runtime(t,vm) * vm.cost; }).sum(); }

    private static Datacenter createDatacenter(CloudSimPlus simulation,int dcId) {
        List<Host> hosts = new ArrayList<>(); int firstHost = dcId == 1 ? 0 : 2; int[][] specs = dcId == 1 ? new int[][]{{8,3000},{8,2500}} : new int[][]{{12,2200},{16,1800}};
        for (int i = 0; i < 2; i++) { List<Pe> pes = new ArrayList<>(); for (int p = 0; p < specs[i][0]; p++) pes.add(new PeSimple(specs[i][1])); HostSimple host = new HostSimple(dcId == 1 ? 32768 : (i == 0 ? 49152 : 65536),10_000_000,1_000_000,pes); host.setId(firstHost + i); host.setVmScheduler(new VmSchedulerTimeShared()); hosts.add(host); }
        return new DatacenterSimple(simulation,hosts,new StaticVmAllocationPolicy());
    }

    private static Vm createVm(VmSpec spec) {
        VmSimple vm = new VmSimple(spec.mips, spec.pes);
        vm.setId(spec.id);
        vm.setRam(spec.ramMb);
        vm.setBw(1000);
        vm.setSize(10_000);
        vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
        return vm;
    }

    private static final class StaticVmAllocationPolicy extends VmAllocationPolicySimple {
        @Override public org.cloudsimplus.hosts.HostSuitability allocateHostForVm(Vm vm) {
            int hostId = vm.getId() < 2 ? 0 : vm.getId() < 4 ? 1 : vm.getId() < 6 ? 2 : 3;
            return getHostList().stream().filter(h -> h.getId() == hostId).findFirst()
                .map(h -> h.createVm(vm)).orElse(org.cloudsimplus.hosts.HostSuitability.NULL);
        }
    }

    private static void printOutput(List<Cloudlet> cloudlets,List<VmSpec> vms,String algorithm,double makespan,int dryRun,int repeat) {
        System.out.printf("%n================ %s dry run %d repeat %d ================%n",algorithm,dryRun,repeat); System.out.printf("%-12s %-10s %-15s %-10s %-14s %-14s %-14s%n","Cloudlet ID","STATUS","Data center ID","VM ID","Time","Start Time","Finish Time");
        cloudlets.stream().sorted(Comparator.comparingDouble(Cloudlet::getFinishTime)).forEach(c -> { int vmId = (int)c.getVm().getId(); System.out.printf(Locale.US,"%d %-10s %02d              %02d        %10.2f      %10.2f      %10.2f%n",c.getId(),c.getStatus(),vms.get(vmId).dcId,vmId,c.getActualCpuTime(),c.getExecStartTime(),c.getFinishTime()); });
        System.out.printf(Locale.US,"Makespan using %s: %.6f%n",algorithm,makespan); System.out.println("========================================================");
    }

    private static void writeConfig(int rows) throws IOException { try (BufferedWriter w = Files.newBufferedWriter(RESULTS.resolve("configuration.txt"))) { w.write("cloudsim-plus=8.0.0\nsource_rows=" + rows + "\nrequired_unique_rows=45000\nworkload_sizes=1000,2000,3000,4000,5000\nrepeats_per_size=3\nalgorithms=SMA,AO\nobjective_weights=time:0.60,cost:0.40\npopulation=" + POPULATION_SIZE + "\niterations=" + ITERATIONS + "\ntopology=2 datacenters; 2 hosts per datacenter; 2 VMs per host\n"); } }

    private static void writeRuns(List<RunResult> rows) throws IOException { try (BufferedWriter w = Files.newBufferedWriter(RESULTS.resolve("runs.csv"))) { w.write("dry_run,repeat,start_row,end_row,workload_size,algorithm,makespan,total_cost,cpu_utilization,throughput,degree_imbalance,deadline_miss_ratio,budget,budget_exceeded\n"); for (RunResult r : rows) w.write(String.format(Locale.US,"%d,%d,%d,%d,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s%n",r.dryRun,r.repeat,r.startRow,r.endRow,r.workloadSize,r.algorithm,r.makespan,r.totalCost,r.cpuUtilization,r.throughput,r.imbalance,r.deadlineMissRatio,r.budget,r.budgetExceeded)); } }

    private static void writeSummary(List<RunResult> rows) throws IOException {
        record Key(String algorithm,int size) {}
        Map<Key,List<RunResult>> groups = rows.stream().collect(Collectors.groupingBy(r -> new Key(r.algorithm,r.workloadSize),LinkedHashMap::new,Collectors.toList()));
        try (BufferedWriter w = Files.newBufferedWriter(RESULTS.resolve("summary.csv"))) { w.write("algorithm,workload_size,runs,makespan_mean,makespan_sd,cost_mean,cost_sd,cpu_util_mean,throughput_mean,imbalance_mean,deadline_miss_mean,budget_exceed_count\n"); for (var e : groups.entrySet()) writeSummaryRow(w,e.getKey().algorithm,e.getKey().size,e.getValue()); for (String a : ALGORITHMS) { List<RunResult> all = rows.stream().filter(r -> r.algorithm.equals(a)).toList(); if (!all.isEmpty()) writeSummaryRow(w,a,0,all); } }
    }

    private static void writeSummaryRow(BufferedWriter w,String algorithm,int size,List<RunResult> rows) throws IOException { w.write(String.format(Locale.US,"%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d%n",algorithm,size,rows.size(),mean(rows,RunResult::makespan),sd(rows,RunResult::makespan),mean(rows,RunResult::totalCost),sd(rows,RunResult::totalCost),mean(rows,RunResult::cpuUtilization),mean(rows,RunResult::throughput),mean(rows,RunResult::imbalance),mean(rows,RunResult::deadlineMissRatio),rows.stream().filter(RunResult::budgetExceeded).count())); }
    private static double mean(List<RunResult> rows,Function<RunResult,Double> metric) { return rows.stream().mapToDouble(r -> metric.apply(r)).average().orElse(0); }
    private static double sd(List<RunResult> rows,Function<RunResult,Double> metric) { if (rows.size() < 2) return 0; double m = mean(rows,metric); return Math.sqrt(rows.stream().mapToDouble(r -> { double d = metric.apply(r) - m; return d * d; }).sum() / (rows.size() - 1)); }
}
