# SOKA Metaheuristic Cloud Scheduling (SMA vs AO)

Proyek ini membandingkan kinerja dua algoritma metaheuristik modern berbasis populasi, yaitu **Slime Mould Algorithm (SMA)** dan **Aquila Optimizer (AO)**, dalam memecahkan masalah penjadwalan komputasi awan multi-datacenter heterogen menggunakan **CloudSim Plus 8.0.0** dan data riil **Google ClusterData 2019**.

Dokumen laporan resmi lengkap dengan struktur bab akademik telah dibuat di berkas: **[`laporan.md`](laporan.md)**.

---

## DAFTAR ISI LAPORAN & RINGKASAN SISTEMATIKA

- **[BAB 1: Pendahuluan & Ringkasan Eksekutif](laporan.md#bab-1-pendahuluan--ringkasan-eksekutif)**: Latar belakang optimasi multi-objektif, perbandingan SMA vs AO, dan temuan kunci.
- **[BAB 2: Karakteristik Workload & Dataset](laporan.md#bab-2-karakteristik-workload--dataset)**: Pengambilan 45.000 baris task unik Google Trace 2019, formula pemetaan atribut, dan skema partisi 1.000–5.000 cloudlet.
- **[BAB 3: Desain Arsitektur & Topologi Cloud](laporan.md#bab-3-desain-arsitektur--topologi-cloud)**: Spesifikasi 2 Datacenter, 4 Host fisik, dan penempatan 8 Virtual Machine heterogen.
- **[BAB 4: Formulasi Metode & Algoritma Metaheuristik](laporan.md#bab-4-formulasi-metode--algoritma-metaheuristik)**: Fungsi objektif bobot 60:40 (waktu:biaya), mekanisme *repair*, serta logika diskrit SMA dan AO.
- **[BAB 5: Hasil Simulasi & Pembahasan Analisis](laporan.md#bab-5-hasil-simulasi--pembahasan-analisis)**: Tabel komparasi lengkap metrik Makespan, Cost, Utilisasi CPU, Throughput, dan Imbalance.
- **[BAB 6: Kesimpulan & Rekomendasi](laporan.md#bab-6-kesimpulan--rekomendasi)**: Kesimpulan performa kedua algoritma dan rekomendasi parameter untuk pengujian lanjutan.

---

## 1. Topologi Infrastruktur Statis

Simulasi mengonfigurasi arsitektur cloud dengan 2 Datacenter, 4 Host fisik, dan 8 Virtual Machine:

```
[Datacenter 1]
 ├── Host 0 (8 PE @ 3000 MIPS, 32 GB RAM) ──► VM 0 & VM 1 (Small: 1 PE, 1000 MIPS, Cost 0.010)
 └── Host 1 (8 PE @ 2500 MIPS, 32 GB RAM) ──► VM 2 (Medium: 2 PE, 1800 MIPS, Cost 0.018)
                                           ──► VM 3 (Large:  4 PE, 2500 MIPS, Cost 0.032)

[Datacenter 2]
 ├── Host 2 (12 PE @ 2200 MIPS, 48 GB RAM) ─► VM 4 & VM 5 (Small: 1 PE, 1000 MIPS, Cost 0.010)
 └── Host 3 (16 PE @ 1800 MIPS, 64 GB RAM) ─► VM 6 (Medium: 2 PE, 1800 MIPS, Cost 0.018)
                                           ─► VM 7 (Large:  4 PE, 2500 MIPS, Cost 0.032)
```

---

## 2. Skema Beban Kerja (Workload Schedule)

Dataset input adalah `data/workload.csv` (minimal 45.000 baris unik dari Google ClusterData 2019):
- **Dry Run 1**: 1.000 task $\times$ 3 repetisi = 3.000 task (baris 1–3.000)
- **Dry Run 2**: 2.000 task $\times$ 3 repetisi = 6.000 task (baris 3.001–9.000)
- **Dry Run 3**: 3.000 task $\times$ 3 repetisi = 9.000 task (baris 9.001–18.000)
- **Dry Run 4**: 4.000 task $\times$ 3 repetisi = 12.000 task (baris 18.001–30.000)
- **Dry Run 5**: 5.000 task $\times$ 3 repetisi = 15.000 task (baris 30.001–45.000)
- **Total Pengujian**: 5 ukuran beban $\times$ 3 repetisi $\times$ 2 algoritma = **30 kali simulasi CloudSim**.

---

## 3. Ringkasan Hasil Eksperimen

Data hasil valid yang sudah diuji tersimpan pada direktori [`artifacts/remote-results-5000/`](artifacts/remote-results-5000/):

| Metrik Evaluasi | Slime Mould Algorithm (SMA) | Aquila Optimizer (AO) | Pemenang / Keunggulan |
| :--- | :---: | :---: | :---: |
| **Makespan Rata-rata** | **208.211.070 detik** | 208.711.485 detik | **SMA (+0,24% lebih cepat)** |
| **Biaya (*Cost*) Rata-rata** | 23.914.429 MU | **23.909.615 MU** | **AO (+0,02% lebih hemat)** |
| **CPU Utilization Rata-rata** | **34,22%** | 34,06% | **SMA** |
| **Throughput Rata-rata** | **$1,446 \times 10^{-5}$ task/s** | $1,444 \times 10^{-5}$ task/s | **SMA** |
| **Load Imbalance Rata-rata** | **0,7749** | 0,7751 | **SMA** |

---

## 4. Cara Menjalankan Uji Coba

### Mengunduh Workload (45.000 Baris):
```bash
python scripts/download_google_trace_sample.py --cell a --format parquet --limit 45000 --max-shards 64 --output data/workload.csv
```

### Eksekusi Otomatis Melalui Script Bash:
```bash
chmod +x run.sh
./run.sh                  # Menjalankan SMA dan AO
./run.sh --algorithm SMA  # Hanya SMA
./run.sh --algorithm AO   # Hanya AO
```

### Eksekusi Menggunakan Maven:
```bash
mvn clean compile exec:java
```

### Berkas Keluaran Hasil:
- `results/runs.csv`: Data detail setiap run (30 baris).
- `results/summary.csv`: Rata-rata dan standar deviasi per ukuran beban dan overall.
- `results/comparison.png`: Grafik kurva komparasi metrik dari 1.000 hingga 5.000 task.
- `results/overall.png`: Diagram batang agregat perbandingan performa.
