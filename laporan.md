# LAPORAN RESMI EKSPERIMEN PENJADWALAN KOMPUTASI AWAN
## KOMPARASI KINERJA ALGORITMA METAHEURISTIK: SLIME MOULD ALGORITHM (SMA) DAN AQUILA OPTIMIZER (AO) PADA CLOUDSIM PLUS

---

## BAB 1: PENDAHULUAN & RINGKASAN EKSEKUTIF

### 1.1 Latar Belakang & Tujuan
Penjadwalan tugas (*task scheduling*) pada infrastruktur komputasi awan heterogen merupakan permasalahan optimasi NP-Hard. Komputasi awan modern menghadapi *trade-off* fundamental antara:
1. **Minimasi Makespan**: Mempercepat penyelesaian seluruh antrian komputasi (*time efficiency*).
2. **Minimasi Biaya Eksekusi (*Cost*)**: Menekan biaya sewa pemakaian CPU/RAM pada mesin virtual (*financial cost efficiency*).

Eksperimen ini bertujuan mengevaluasi dan membandingkan secara empiris kinerja dua algoritma metaheuristik modern berbasis populasi:
- **Slime Mould Algorithm (SMA)**: Algoritma yang mengadopsi dinamika osilasi dan umpan balik biologis kapang lendir (*Physarum polycephalum*) dalam mencari rute makanan optimal.
- **Aquila Optimizer (AO)**: Algoritma yang memodelkan 4 perilaku berburu elang Aquila (dari penjelajahan langit tinggi hingga penangkapan mangsa jarak dekat).

Keduanya diuji pada model infrastruktur multi-datacenter heterogen menggunakan simulator **CloudSim Plus 8.0.0** dengan dataset riil **Google ClusterData 2019**.

### 1.2 Ringkasan Hasil Kunci
Berdasarkan 30 kali pengujian valid (*3 repetisi x 5 variasi beban kerja x 2 algoritma*) pada rentang 1.000 hingga 5.000 cloudlet:
- **Makespan Rata-rata Keseluruhan**: **SMA** mencatatkan waktu rata-rata yang lebih cepat yaitu **$208,21 \times 10^6$ detik**, mengungguli **AO** (**$208,71 \times 10^6$ detik**) sebesar ~0,24%. SMA lebih unggul pada beban 1.000, 4.000, dan 5.000 cloudlet.
- **Total Biaya Operasional (*Cost*)**: **AO** mencatatkan efisiensi biaya rata-rata yang sedikit lebih hemat yaitu **$23,909 \times 10^6$ MU**, dibandingkan SMA (**$23,914 \times 10^6$ MU**). AO unggul dalam efisiensi biaya pada ukuran 1.000, 2.000, 3.000, dan 4.000 cloudlet.
- **Karakteristik Komparatif**: Tidak ada algoritma yang menang mutlak di seluruh metrik. SMA lebih condong mengeksploitasi penugasan ke VM bertenaga tinggi untuk memangkas makespan, sementara AO mempertahankan utilisasi yang sedikit lebih hemat biaya.

---

## BAB 2: KARAKTERISTIK WORKLOAD & DATASET

### 2.1 Sumber Data Google ClusterData 2019
Dataset input bersumber dari repositori publik Google Cluster Workload Trace 2019 (`clusterdata_2019_a`) yang disaring menggunakan `scripts/download_google_trace_sample.py`. Kriteria pemfilteran hanya mempertahankan tugas yang memiliki siklus hidup bersih:
- Event alur: `SUBMIT` $\to$ tepat satu `SCHEDULE` $\to$ `FINISH` tanpa interupsi (`EVICT`, `FAIL`, `KILL` $= 0$).
- Nilai durasi, alokasi CPU core, dan memori RAM bernilai positif ($> 0$).

### 2.2 Transformasi Atribut Tugas ke Cloudlet
| Atribut Trace Google | Parameter CloudSim Plus | Formula / Aturan Pemetaan |
| :--- | :--- | :--- |
| `task_id` | Cloudlet ID | Identifier unik tugas integer numerik |
| `start_time` | Submission Delay | Waktu kedatangan relatif (detik): $(t_{\text{submit}} - t_0) / 10^6$ |
| `execution_duration` | Cloudlet Length (MI) | $\text{Length} = \text{duration (detik)} \times 1.000\text{ MIPS}$ |
| `cpu_request` | Pes (vCPU Core) | Kuartil trace: dipetakan ke 1, 2, atau 4 Core |
| `memory_request_mb` | RAM Request (MB) | Kuartil trace: dipetakan ke 512, 1.024, 2.048, atau 4.096 MB |

### 2.3 Skema Partisi Workload (Contiguous & Non-Overlapping)
Eksperimen menggunakan **45.000 baris task unik**. Data dikonsumsi secara sekuensial tanpa tumpang tindih (*non-overlapping contiguous chunks*) untuk menguji skalabilitas:

| Dry Run | Ukuran Beban (*Tasks*) | Repetisi | Rentang Baris Data (*Row Range*) | Total Task Terpakai |
| :---: | :---: | :---: | :---: | :---: |
| **Dry Run 1** | 1.000 | 3 | Rep 1: 1–1.000; Rep 2: 1.001–2.000; Rep 3: 2.001–3.000 | 3.000 baris |
| **Dry Run 2** | 2.000 | 3 | Rep 1: 3.001–5.000; Rep 2: 5.001–7.000; Rep 3: 7.001–9.000 | 6.000 baris |
| **Dry Run 3** | 3.000 | 3 | Rep 1: 9.001–12.000; Rep 2: 12.001–15.000; Rep 3: 15.001–18.000 | 9.000 baris |
| **Dry Run 4** | 4.000 | 3 | Rep 1: 18.001–22.000; Rep 2: 22.001–26.000; Rep 3: 26.001–30.000 | 12.000 baris |
| **Dry Run 5** | 5.000 | 3 | Rep 1: 30.001–35.000; Rep 2: 35.001–40.000; Rep 3: 40.001–45.000 | 15.000 baris |
| **Total** | - | **15 run / alg** | **Baris 1 s/d 45.000 (100% data unik)** | **45.000 baris** |

---

## BAB 3: DESAIN ARSITEKTUR & TOPOLOGI CLOUD

### 3.1 Spesifikasi Datacenter dan Host Fisik
Simulasi dimodelkan dengan 2 Datacenter dan 4 Host Server fisik heterogen:
- **Datacenter 1**:
  - `Host 0`: 8 Core $\times$ 3.000 MIPS, 32 GB RAM, 10 Gbps BW (Menampung VM 0 dan VM 1)
  - `Host 1`: 8 Core $\times$ 2.500 MIPS, 32 GB RAM, 10 Gbps BW (Menampung VM 2 dan VM 3)
- **Datacenter 2**:
  - `Host 2`: 12 Core $\times$ 2.200 MIPS, 48 GB RAM, 10 Gbps BW (Menampung VM 4 dan VM 5)
  - `Host 3`: 16 Core $\times$ 1.800 MIPS, 64 GB RAM, 10 Gbps BW (Menampung VM 6 dan VM 7)

### 3.2 Spesifikasi 8 Virtual Machine (VM)
| VM ID | Tipe VM | vCPU (PE) | MIPS/PE | Total MIPS | RAM (MB) | Tarif Biaya ($MU/s$) | Host Fisik | Datacenter |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **VM 0** | Small | 1 | 1.000 | 1.000 | 2.048 | 0,010 | Host 0 | Datacenter 1 |
| **VM 1** | Small | 1 | 1.000 | 1.000 | 2.048 | 0,010 | Host 0 | Datacenter 1 |
| **VM 2** | Medium | 2 | 1.800 | 3.600 | 4.096 | 0,018 | Host 1 | Datacenter 1 |
| **VM 3** | Large | 4 | 2.500 | 10.000 | 8.192 | 0,032 | Host 1 | Datacenter 1 |
| **VM 4** | Small | 1 | 1.000 | 1.000 | 2.048 | 0,010 | Host 2 | Datacenter 2 |
| **VM 5** | Small | 1 | 1.000 | 1.000 | 2.048 | 0,010 | Host 2 | Datacenter 2 |
| **VM 6** | Medium | 2 | 1.800 | 3.600 | 4.096 | 0,018 | Host 3 | Datacenter 2 |
| **VM 7** | Large | 4 | 2.500 | 10.000 | 8.192 | 0,032 | Host 3 | Datacenter 2 |

- **Kebijakan Alokasi Host**: `StaticVmAllocationPolicy` memetakan VM ke Host secara deterministik.
- **Penjadwalan Cloudlet di VM**: `CloudletSchedulerSpaceShared` (core vCPU dialokasikan secara dedicated selama task berjalan).

---

## BAB 4: FORMULASI METODE & ALGORITMA METAHEURISTIK

### 4.1 Fungsi Objektif Multi-Objective
Evaluasi setiap kromosom/kandidat alokasi VM menggunakan bobot linear 60% Makespan dan 40% Cost:
$$\text{Fitness} = 0,60 \times \left( \frac{\text{Makespan}}{\text{ScaleTime}} \right) + 0,40 \times \left( \frac{\text{Total Cost}}{\text{ScaleCost}} \right)$$

Di mana:
- $\text{ScaleTime} = \sum_{i=1}^N \min_{v \in \text{Feasible}} \left( \frac{\text{Length}_i}{\text{PE}_v \times \text{MIPS}_v} \right)$
- $\text{ScaleCost} = \sum_{i=1}^N \text{runtime}(t_i, v_{\text{cheapest}}) \times \text{cost}_{v_{\text{cheapest}}}$

### 4.2 Parameter Optimasi
- **Ukuran Populasi (*Population Size*)**: $N = 8$
- **Jumlah Iterasi (*Iterations*)**: $T = 12$
- **Mekanisme Perbaikan (*Repair Function*)**: Jika alokasi menugaskan task ke VM yang tidak memenuhi kapasitas ($PE_{\text{task}} > PE_{\text{VM}}$ atau $RAM_{\text{task}} > RAM_{\text{VM}}$), algoritma otomatis memindahkan task ke VM termurah yang memenuhi syarat (*cheapest feasible VM*).

### 4.3 Logika Diskrit Algoritma SMA
1. Menghitung peringkat (*rank*) kandidat berdasarkan nilai fitness.
2. Menghitung rentang fitness dan bobot osilasi $W$:
   - Solusi dengan peringkat atas ditarik mendekati posisi terbaik saat ini ($X_{\text{best}}$).
   - Solusi berperingkat bawah mempertahankan probabilitas eksplorasi acak.
3. Parameter eksplorasi acak $z$ meningkat bertahap seiring iterasi ($z = 0,03 + 0,07 \times \text{progress}$).

### 4.4 Logika Diskrit Algoritma AO
AO dibagi menjadi 4 strategi pencarian berdasarkan fase iterasi:
1. **Fase Eksplorasi ($\text{iterasi} < \frac{2}{3} T$)**:
   - *Strategi 1 (High-Soaring)*: Membandingkan posisi dengan posisi rata-rata populasi (*mean position*).
   - *Strategi 2 (Contour Flight)*: Bergerak di antara posisi rata-rata populasi dan posisi terbaik $X_{\text{best}}$.
2. **Fase Eksploitasi ($\text{iterasi} \ge \frac{2}{3} T$)**:
   - *Strategi 3 (Low-Flight)*: Pencarian lokal di sekitar $X_{\text{best}}$ dengan radius neighborhood yang menyusut.
   - *Strategi 4 (Walk-and-Grab)*: Penyesuaian mikro/geseran lokal ($[-1, 0, +1]$) di sekitar $X_{\text{best}}$.

---

## BAB 5: HASIL SIMULASI & PEMBAHASAN ANALISIS

Data hasil simulasi ini diambil dari rekam jejak pengujian server di [`artifacts/remote-results-5000/summary.csv`](file:///d:/soka/soka-metaheuristic/artifacts/remote-results-5000/summary.csv).

### 5.1 Tabel Perbandingan Kinerja Rata-rata per Ukuran Workload

| Workload Size | Algoritma | Makespan Mean (detik) | Makespan SD | Cost Mean (MU) | Cost SD | CPU Util Mean | Throughput Mean | Load Imbalance |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1.000** | **SMA** | **90.156.631** | 9.940.662 | 10.582.237 | 306.416 | **0,328** | $1,11 \times 10^{-5}$ | **0,856** |
| | **AO** | 92.702.139 | 9.041.667 | **10.563.558** | 322.073 | 0,316 | $1,09 \times 10^{-5}$ | 0,872 |
| **2.000** | **SMA** | 127.564.850 | 9.925.407 | 17.077.487 | 1.552.298 | 0,399 | $1,57 \times 10^{-5}$ | 0,731 |
| | **AO** | **125.613.682** | 10.491.309 | **17.011.049** | 1.495.579 | **0,410** | **$1,60 \times 10^{-5}$** | **0,717** |
| **3.000** | **SMA** | 213.612.839 | 39.881.106 | 20.562.348 | 3.890.618 | **0,284** | $1,46 \times 10^{-5}$ | 0,800 |
| | **AO** | **213.521.156** | 38.350.506 | **20.553.040** | 3.917.163 | 0,282 | $1,46 \times 10^{-5}$ | **0,794** |
| **4.000** | **SMA** | **268.516.123** | 28.660.976 | 31.591.108 | 5.547.716 | **0,354** | **$1,51 \times 10^{-5}$** | **0,731** |
| | **AO** | 269.538.125 | 29.750.935 | **31.567.982** | 5.548.799 | 0,353 | $1,50 \times 10^{-5}$ | 0,733 |
| **5.000** | **SMA** | **341.204.909** | 25.906.196 | 39.758.966 | 4.987.278 | **0,346** | **$1,48 \times 10^{-5}$** | **0,756** |
| | **AO** | 342.182.324 | 22.472.025 | 39.852.446 | 5.040.813 | 0,342 | $1,47 \times 10^{-5}$ | 0,760 |

---

### 5.2 Tabel Agregat Keseluruhan (15 Run per Algoritma)

| Metrik Evaluasi | Slime Mould Algorithm (SMA) | Aquila Optimizer (AO) | Algoritma Unggul |
| :--- | :---: | :---: | :---: |
| **Overall Makespan Mean** | **208.211.070 detik** | 208.711.485 detik | **SMA (+0,24% lebih cepat)** |
| **Overall Makespan SD** | 97.079.695 | **97.030.207** | **AO (Sedikit lebih stabil)** |
| **Overall Cost Mean** | 23.914.429 MU | **23.909.615 MU** | **AO (+0,02% lebih hemat)** |
| **Overall Cost SD** | **11.287.496** | 11.329.994 | **SMA** |
| **Overall CPU Utilization** | **0,3422 (34,22%)** | 0,3406 (34,06%) | **SMA** |
| **Overall Throughput** | **$1,446 \times 10^{-5}$ task/s** | $1,444 \times 10^{-5}$ task/s | **SMA** |
| **Overall Load Imbalance** | **0,7749** | 0,7751 | **SMA** |
| **Deadline Miss Ratio** | 0,9978 | **0,9977** | **AO** |

---

### 5.3 Analisis & Pembahasan Mendalam

#### 1. Analisis Makespan
- Pertumbuhan makespan meningkat secara linear terhadap jumlah cloudlet: dari ~90–92 juta detik pada beban 1.000 cloudlet menjadi ~341–342 juta detik pada beban 5.000 cloudlet.
- SMA mencatatkan keunggulan makespan pada beban terkecil (1.000) dan dua beban terbesar (4.000 dan 5.000). Hal ini dipicu oleh sifat adaptif bobot osilasi kapang lendir yang cepat mengarahkan cloudlet ke VM Large (VM 3 dan VM 7) saat antrian memadat.

#### 2. Analisis Biaya Operasional (Cost)
- AO mencatatkan total biaya yang lebih hemat secara konsisten pada ukuran 1.000 hingga 4.000 cloudlet.
- Strategi penjelajahan kontur (*contour flight*) dan penyesuaian lokal pada AO mendistribusikan sejumlah tugas ke VM Medium atau Small yang berbiaya lebih murah ($0,010 dan $0,018 MU/detik), sehingga menekan pengeluaran finansial.

#### 3. Analisis Utilisasi CPU & Throughput
- Utilisasi CPU kedua algoritma berada pada rentang **31% hingga 41%**. Rata-rata utilisasi tertinggi dicapai pada beban 2.000 cloudlet (~40–41%).
- Throughput stabil pada kisaran **$1,4 \times 10^{-5}$ hingga $1,6 \times 10^{-5}$ task/detik**. Hal ini disebabkan oleh pembatasan kapasitas core fisik pada 8 VM yang harus melayani hingga 5.000 task per batch.

#### 4. Keseimbangan Beban (*Degree of Imbalance*)
- Nilai load imbalance berkisar antara **0,71 hingga 0,87**.
- Ketidakseimbangan ini wajar terjadi pada infrastruktur heterogen karena VM Large (4 vCPU, 2.500 MIPS) memproses beban kerja yang jauh lebih besar dan lebih lama dibanding VM Small (1 vCPU, 1.000 MIPS).

---

## BAB 6: KESIMPULAN & REKOMENDASI

### 6.1 Kesimpulan
1. **Validitas Eksperimen**: Seluruh 30 run simulasi CloudSim Plus berhasil diselesaikan secara deterministik tanpa pelanggaran alokasi VM dan mengonsumsi 45.000 baris workload unik Google Trace 2019.
2. **Komparasi Kinerja**:
   - **SMA lebih unggul untuk prioritas Waktu (Makespan)**: Direkomendasikan untuk beban kerja komputasi yang menuntut durasi penyelesaian secepat mungkin.
   - **AO lebih unggul untuk prioritas Biaya (Cost)**: Direkomendasikan untuk penyedia layanan yang berorientasi pada efisiensi anggaran pengeluaran sumber daya.
3. **Konvergensi pada Iterasi Rendah**: Dengan konfigurasi 8 populasi dan 12 iterasi, kedua algoritma metaheuristik sudah mampu menghasilkan solusi alokasi yang stabil dan mematuhi batasan heterogenitas sistem.

### 6.2 Rekomendasi untuk Eksperimen Lanjutan
- **Peningkatan Parameter**: Menguji penambahan ukuran populasi (misal $N=20$) dan jumlah iterasi (misal $T=50$) untuk mengamati apakah AO atau SMA dapat menemukan solusi optimal global yang lebih tajam.
- **Variasi Bobot Objektif**: Melakukan pengujian sensitivitas bobot objektif selain 60:40 (misalnya 80:20 untuk fokus makespan murni atau 20:80 untuk fokus biaya murni).
- **Pengujian Kode Algoritma Terbaru**: Mengeksekusi simulasi menggunakan kode formulasi diskrit hasil pembaruan commit `e10c81a` secara lokal untuk membandingkannya dengan baseline data remote saat ini.

