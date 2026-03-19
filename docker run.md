# Steps to Run the Application

## Prerequisites
Make sure these are installed on your machine:
- **Docker Desktop** → [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)
- That's it — Java and MySQL do NOT need to be installed locally.

---

## Option A — Run with Docker (Recommended)

### Step 1 — Open terminal in the project folder
```powershell
cd C:\Users\mkumar27\OT\web-socket
```

### Step 2 — Verify your `.env` file has the correct values
```powershell
Get-Content .env
```
Make sure `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` are filled in.

### Step 3 — Build and start everything
```powershell
docker compose up --build
```
This will:
1. Pull `mysql:8.0` image
2. Build your Spring Boot app into a Docker image
3. Start MySQL, wait for it to be healthy
4. Start the app

> First run takes ~3–5 minutes (downloads images + builds JAR).
> Subsequent runs take ~30 seconds.

### Step 4 — Open the app
```
http://localhost:8080/api/v1/login
```

### Step 5 — Stop the app
```powershell
# Stop but keep the database data
docker compose down

# Stop AND wipe the database (fresh start)
docker compose down -v
```

---

## Option B — Run Locally (without Docker)

### Prerequisites
- Java 17 installed
- MySQL 8 running locally on port `3306`
- Database `otdb` created (or it auto-creates)

### Step 1 — Start MySQL locally and make sure it's running

### Step 2 — Run the app
```powershell
cd C:\Users\mkumar27\OT\web-socket
.\mvnw.cmd spring-boot:run
```

### Step 3 — Open the app
```
http://localhost:8080/api/v1/login
```

---

## Useful Docker Commands

| Command | What it does |
|---|---|
| `docker compose up --build` | Build image + start all containers |
| `docker compose up --build -d` | Same but runs in background |
| `docker compose down` | Stop and remove containers |
| `docker compose down -v` | Stop, remove containers + wipe DB volume |
| `docker compose logs -f app` | Stream logs from the Spring Boot app |
| `docker compose logs -f mysql` | Stream logs from MySQL |
| `docker compose ps` | Check status of all containers |
| `docker compose restart app` | Restart only the app container |

---

## Page URLs

| Page | URL |
|---|---|
| Landing | `http://localhost:8080/api/v1/` |
| Login | `http://localhost:8080/api/v1/login` |
| Register | `http://localhost:8080/api/v1/register` |
| Chat | `http://localhost:8080/api/v1/chat` |
| Admin Dashboard | `http://localhost:8080/api/v1/dashboard` |

---

## DB Credentials (inside Docker)

| | Value |
|---|---|
| Host | `localhost:3306` (from your machine) |
| Database | `otdb` |
| App username | `chatapp` |
| App password | `ChatApp@1617` |
| Root password | `RootPass@1617` |
