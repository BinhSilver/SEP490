# J-LAS - Japanese Learning & Assessment System

J-LAS (Japanese Learning & Assessment System) is a web-based Japanese learning platform designed to support personalized Japanese learning, JLPT preparation, Kanji study, vocabulary lookup, Shadowing practice, AI-assisted learning, progress tracking, and other learning activities.

The project is developed as a graduation project using **Java Spring Boot** for the backend and **MySQL** for data storage.

---

## 1. Main Features

J-LAS is planned to provide the following major features:

* User registration and authentication
* User profile management
* Personalized Japanese learning paths
* JLPT-oriented learning
* Vocabulary and Kanji learning
* Japanese dictionary
* Search by Kanji, Hiragana, Katakana, and Romaji
* Kanji handwriting recognition
* Kanji stroke information
* Shadowing practice
* Learning progress tracking
* Daily learning activities
* AI-assisted learning features
* Global Search
* Premium membership
* Online payment
* Learning content management
* Administrative management

---

## 2. Tech Stack

### Backend

* Java
* Spring Boot
* Spring Web
* Spring Data JPA
* Spring Security
* Maven

### Database

* MySQL

### Version Control

* Git
* GitHub

### External Services

The project may integrate with external services for features such as:

* Payment
* Text-to-Speech
* AI services
* Kanji handwriting recognition

Additional services may be added as the project develops.

---

## 3. Project Structure

The Spring Boot project follows a layered architecture.

```text
j-las/
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── jlas/
│   │   │           ├── config/
│   │   │           ├── controller/
│   │   │           ├── dto/
│   │   │           ├── entity/
│   │   │           ├── exception/
│   │   │           ├── repository/
│   │   │           ├── security/
│   │   │           ├── service/
│   │   │           └── JlasApplication.java
│   │   │
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── static/
│   │       └── templates/
│   │
│   └── test/
│
├── .gitignore
├── mvnw
├── mvnw.cmd
├── pom.xml
└── README.md
```

The structure may be expanded during development depending on project requirements.

---

## 4. Prerequisites

Before running the project, make sure the following tools are installed.

### Required

* Git
* Java JDK
* MySQL
* Maven or Maven Wrapper
* IDE / Code Editor

Recommended IDEs:

* IntelliJ IDEA
* Visual Studio Code
* Antigravity or another Java-compatible IDE

Check Java installation:

```bash
java --version
```

Check Git installation:

```bash
git --version
```

Check Maven installation:

```bash
mvn --version
```

If Maven is not installed globally, the project can use the included Maven Wrapper.

Windows:

```bash
mvnw.cmd --version
```

Git Bash / Linux / macOS:

```bash
./mvnw --version
```

---

## 5. Clone the Project

Clone the Project

Clone the repository:

git clone https://github.com/BinhSilver/SEP490.git

Move into the project directory:

cd SEP490

Repository:

https://github.com/BinhSilver/SEP490

---

## 6. Database Setup

The project uses **MySQL**.

Create a local database:

```sql
CREATE DATABASE jlas
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

The `utf8mb4` character set is recommended because the system stores Japanese characters including:

* Kanji
* Hiragana
* Katakana
* Other Unicode characters

### Dictionary/Kanji test data

The complete Dictionary/Kanji test snapshot is stored with Git LFS. After
cloning, run `git lfs pull`, then follow
[`data/dictionary-seed/README.md`](data/dictionary-seed/README.md) to create a
fully populated `jlas_dictionary_test` database and install KanjiVG assets.
The snapshot contains no account or user/business rows.

### System Admin (JLAS2-51)

On an existing database, run `sql/migrations/028_system_admin_core.sql` once
**before starting the app** (`ddl-auto: validate`); it adds the account-lock
columns, the `admin_audit_log` table and the `SUPER_ADMIN` role. Fresh
databases get them from `jlas_schema.sql`. Promote the first System Admin:

```sql
UPDATE users SET role_id = (SELECT id FROM roles WHERE name = 'SUPER_ADMIN')
WHERE email = '<email>';
```

System Admin pages live under `/admin/system/**` and APIs under
`/api/admin/system/**` (`SUPER_ADMIN` only); moderation stays with
`MODERATOR`/`ADMIN`. A System Admin can lock/unlock accounts, change roles
(`LEARNER`, `CONTENT_CREATOR`, `MODERATOR`, `ADMIN`, `SUPER_ADMIN`) and grant
Premium manually (days stack on an active period; no payment is created).
Every action needs a reason and is written to the audit log.

Demo data for local testing only (public password `SysAdmin@123`):
`sql/seeds/demo_system_admin.sql` creates `sysadmin@jlab.local` (SUPER_ADMIN)
and learner/creator/moderator accounts in different Premium and lock states.
A fresh database that starts cleanly was built in this order: `jlas_schema.sql`,
`sql/jlas_migrations_all.sql`, the seeds `seed_data_jlas.sql`, `jlas_seed_all.sql`,
`questionbank.sql`, `ai_call_scoring_rules.sql`, `demo_content_creator.sql`,
`seed_data_vnjpclub.sql`, then `src/main/resources/db/migrations/jlpt_full_setup.sql`
(required: the JLPT entities validate against it) and `demo_system_admin.sql`.

---

## 7. Application Configuration

Database credentials and sensitive information should **not be committed directly to GitHub**.

Example local configuration:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/jlas?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh

spring.datasource.username=your_mysql_username
spring.datasource.password=your_mysql_password
```

Replace:

```text
your_mysql_username
your_mysql_password
```

with your local MySQL credentials.

---

## 8. Environment Variables

Sensitive configuration should preferably be stored using environment variables.

Example:

```env
DB_HOST=localhost
DB_PORT=3306
DB_NAME=jlas
DB_USERNAME=root
DB_PASSWORD=your_password
```

Other secrets may later include:

```env
JWT_SECRET=
PAYMENT_API_KEY=
TTS_API_KEY=
AI_API_KEY=
```

Never commit real secret keys, passwords, tokens, or API credentials to Git.

---

## 9. Run the Application

### Using Maven Wrapper on Windows

```bash
mvnw.cmd spring-boot:run
```

### Using Maven Wrapper on Git Bash / Linux / macOS

```bash
./mvnw spring-boot:run
```

### Using Maven

```bash
mvn spring-boot:run
```

After the application starts successfully, the backend will normally be available at:

```text
http://localhost:8080
```

---

## 10. Build the Project

Build the application:

```bash
./mvnw clean package
```

Windows CMD or PowerShell:

```bash
mvnw.cmd clean package
```

The generated `.jar` file will be located in:

```text
target/
```

Run the generated application:

```bash
java -jar target/<generated-file>.jar
```

---

## 11. Run Tests

Run all tests:

```bash
./mvnw test
```

Windows:

```bash
mvnw.cmd test
```

Developers should make sure relevant tests pass before creating a Pull Request or merging code.

---

## 12. Git Workflow

To reduce conflicts and protect the main codebase, developers should avoid developing directly on the `main` branch.

Recommended branches:

```text
main
develop
feature/*
fix/*
hotfix/*
```

### `main`

Contains stable project code.

Do not push unfinished features directly to `main`.

### `develop`

Integration branch for current development.

Completed features should normally be merged into `develop` before being released to `main`.

### `feature/*`

Used for new features.

Example:

```text
feature/authentication
feature/dictionary
feature/kanji-recognition
feature/shadowing
feature/learning-path
```

### `fix/*`

Used for normal bug fixes.

Example:

```text
fix/login-validation
```

### `hotfix/*`

Used for urgent fixes affecting stable code.

Example:

```text
hotfix/payment-error
```

---

## 13. Creating a New Branch

Always update your local branch before starting new work.

```bash
git checkout develop
git pull origin develop
```

Create a new feature branch:

```bash
git checkout -b feature/<feature-name>
```

Example:

```bash
git checkout -b feature/dictionary
```

---

## 14. Commit Convention

Commits should clearly describe the changes made.

Recommended format:

```text
<type>: <description>
```

Common types:

```text
feat
fix
refactor
docs
test
chore
style
```

Examples:

```text
feat: add user registration API
```

```text
feat: implement dictionary search
```

```text
fix: resolve login validation error
```

```text
refactor: update vocabulary service
```

```text
docs: update README
```

```text
test: add authentication service tests
```

Avoid unclear commit messages such as:

```text
update
```

```text
fix
```

```text
abc
```

```text
done
```

---

## 15. Before Pushing Code

Before pushing your code, update your branch with the latest code from the integration branch.

Example:

```bash
git checkout develop
git pull origin develop
```

Return to your feature branch:

```bash
git checkout feature/<feature-name>
```

Merge the latest development code:

```bash
git merge develop
```

Resolve any conflicts locally before pushing.

Then push:

```bash
git push origin feature/<feature-name>
```

---

## 16. Pull Request Workflow

Recommended workflow:

```text
Create Task
    ↓
Create Feature Branch
    ↓
Develop Feature
    ↓
Test Locally
    ↓
Commit
    ↓
Pull Latest Code
    ↓
Resolve Conflicts
    ↓
Push Feature Branch
    ↓
Create Pull Request
    ↓
Code Review
    ↓
Merge into Develop
    ↓
Update Task Status
```

Do not merge code that:

* Does not compile
* Causes existing functions to fail
* Contains unresolved conflicts
* Contains passwords or API keys
* Contains unnecessary generated files
* Has not been tested locally

---

## 17. Important Git Rules

Team members should follow these rules:

1. Do not develop directly on `main`.
2. Pull the latest code before starting development.
3. Pull the latest code before creating a Pull Request.
4. Do not force push to shared branches unless explicitly agreed by the team.
5. Do not delete another member's code without discussion.
6. Resolve merge conflicts carefully.
7. Never commit passwords, tokens, API keys, or private credentials.
8. Keep commits focused on one logical change whenever possible.
9. Make sure the project runs before pushing.
10. Update the corresponding task status after completing work.

---

## 18. Files That Should Not Be Committed

Typical files that should be excluded using `.gitignore` include:

```gitignore
target/
.idea/
.vscode/
*.iml

.env
.env.*

application-local.properties
application-local.yml

*.log

.DS_Store
Thumbs.db
```

Important:

Do not ignore configuration templates that other developers need.

For example, the repository may contain:

```text
.env.example
```

but should not contain:

```text
.env
```

---

## 19. Example Environment Configuration

An `.env.example` file can be provided:

```env
DB_HOST=localhost
DB_PORT=3306
DB_NAME=jlas
DB_USERNAME=root
DB_PASSWORD=

JWT_SECRET=

PAYMENT_API_KEY=

TTS_API_KEY=

AI_API_KEY=
```

Each developer should create their own:

```text
.env
```

based on:

```text
.env.example
```

---

## 20. Development Guidelines

When developing new backend features, developers should generally follow the flow:

```text
Client Request
      ↓
Controller
      ↓
Service
      ↓
Repository
      ↓
Database
```

Typical responsibilities:

### Controller

Handles HTTP requests and responses.

### Service

Contains business logic.

### Repository

Handles communication with the database.

### Entity

Represents database entities.

### DTO

Represents request and response data exchanged through APIs.

### Config

Contains application configuration.

### Security

Contains authentication and authorization logic.

### Exception

Contains custom exceptions and global exception handling.

---

## 21. API Convention

Recommended API prefix:

```text
/api
```

Example:

```text
/api/auth
/api/users
/api/vocabulary
/api/kanji
/api/dictionary
/api/shadowing
/api/learning-paths
```

Example REST endpoints:

```http
GET /api/vocabulary
GET /api/vocabulary/{id}

POST /api/vocabulary

PUT /api/vocabulary/{id}

DELETE /api/vocabulary/{id}
```

---

## 22. HTTP Methods

Use HTTP methods according to their intended purposes.

| Method | Purpose                     |
| ------ | --------------------------- |
| GET    | Retrieve data               |
| POST   | Create data                 |
| PUT    | Update an entire resource   |
| PATCH  | Partially update a resource |
| DELETE | Delete a resource           |

---

## 23. HTTP Status Codes

Common status codes:

| Code | Meaning               |
| ---- | --------------------- |
| 200  | OK                    |
| 201  | Created               |
| 204  | No Content            |
| 400  | Bad Request           |
| 401  | Unauthorized          |
| 403  | Forbidden             |
| 404  | Not Found             |
| 409  | Conflict              |
| 500  | Internal Server Error |

---

## 24. Future Development

The architecture may be expanded as J-LAS develops.

Possible additional components include:

```text
J-LAS
│
├── Backend
│   └── Spring Boot
│
├── Database
│   └── MySQL
│
├── Frontend
│
├── AI Services
│
├── Kanji Recognition
│
├── Text-to-Speech
│
└── Payment Services
```

If Python is required for AI, machine learning, data processing, or Kanji recognition in the future, it can be added as a separate service without replacing the main Spring Boot backend.

Example future architecture:

```text
Frontend
    │
    ▼
Spring Boot Backend
    │
    ├──────────────► MySQL
    │
    ├──────────────► External APIs
    │
    └──────────────► Python AI Service
```

---

## 25. Project Status

```text
Status: In Development
```

J-LAS is currently under active development as a graduation project.

Features, database structure, APIs, and architecture may be updated during the development process.

---

## 26. Team

This project is developed by the J-LAS development team as part of a graduation project.

---

## 27. License

This project is intended for educational and graduation-project purposes.

Unless otherwise specified, the source code should not be redistributed or used commercially without permission from the project team.

---

## 28. Data Sources (Nguồn dữ liệu)

The digitized learning content and Kanji data are third-party data with their own terms. Attribution below is required and must be kept when the data is displayed or redistributed.

* **Textbook content** (50 lessons, N5/N4: vocabulary, example sentences, grammar, dialogues, audio) — crawled from **Sekai Gen**. Seed file: `sql/seeds/seed_data_jlas.sql`, generated by `tools/generate_seed.py` from `data/seed_jlas/`.
* **Kanji data** (characters, readings, stroke counts, radicals) — **KANJIDIC2**, © [Electronic Dictionary Research and Development Group (EDRDG)](https://www.edrdg.org/), used under the [Creative Commons Attribution-ShareAlike 4.0 License](https://creativecommons.org/licenses/by-sa/4.0/). See the [KANJIDIC project page](https://www.edrdg.org/wiki/index.php/KANJIDIC_Project). Extracted with `extract_kanji_data.py` into `kanji_data_n5n4.json`. The attribution line is shown on the Kanji tab of every lesson page (`templates/textbooks/lesson.html`).

---

# J-LAS

**Japanese Learning & Assessment System**

Learn Japanese smarter with personalized learning, Kanji recognition, dictionary support, Shadowing practice, AI-assisted features, and learning progress tracking.
