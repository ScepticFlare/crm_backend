# Compact CRM Backend

Backend service for the Compact CRM application.

Built with Java and Spring Boot, this backend provides REST APIs for authentication, authorization, lead management, opportunities, customers, follow-ups, employees, activity history, reporting, file handling, and email workflows.

The backend is deployed on Render and serves as the API and business-logic layer for the React frontend.

The CRM is actively used internally by 10+ employees at Compact Systems.

## Overview

The backend handles the application's core business logic, authentication and authorization, database operations, and external service integrations.

The main CRM workflow is:

**Lead → Opportunity → Customer**

The service layer manages these workflows and enforces the application's business rules.

## Architecture

The backend follows a layered Spring Boot architecture:

```text
React Frontend
      |
      | HTTP / REST
      v
REST Controllers
      |
      v
Service Layer
      |
      v
Repository Layer
      |
      v
PostgreSQL
```

### Controllers

Handle HTTP requests, responses, request validation, and API routing.

### Services

Contain application logic and CRM business rules.

### Repositories

Provide database access using Spring Data JPA and Hibernate.

### Security

Spring Security handles authentication and authorization for protected API endpoints.

## Authentication & Authorization

Authentication is implemented using Spring Security and JSON Web Tokens (JWT).

The authentication flow is:

```text
Login
  ↓
Credentials validated
  ↓
JWT generated and signed
  ↓
Client sends Bearer token
  ↓
JWT authentication filter validates token
  ↓
Security context populated
  ↓
Protected endpoint accessed
  ↓
Authorization checks applied
```

The backend uses stateless authentication for protected REST endpoints.

### Roles

The CRM supports three primary roles:

- Admin
- Manager
- Employee

Authentication and authorization are handled separately.

Authentication determines the identity of the user, while authorization determines which operations and resources the user can access.

The application also supports scope-based access where applicable:

- Own
- Team
- All

Access-control logic is centralized so authorization rules can be applied consistently across CRM operations.

## CRM Modules

### Lead Management

The backend supports:

- Lead creation and updates
- Lead status management
- Lead-to-opportunity conversion
- Lead products
- Follow-ups
- Activity history
- Search and filtering
- Server-side pagination
- Import and export functionality

### Opportunity Management

The backend supports:

- Opportunity creation and updates
- Opportunity stage management
- Opportunity products
- Follow-ups
- Customer conversion
- Business-rule validation

The service layer applies business rules around opportunity and customer workflows.

### Customer Management

The backend manages customer records and their relationships with opportunities created through the CRM workflow.

### Follow-ups

Follow-up functionality allows employees to create, update, and track follow-up activities associated with CRM records.

### Employee Management

Employee functionality includes employee records, roles, reporting relationships, and access control required by the CRM.

## Activity & Audit History

The backend maintains activity records for important operations performed within the CRM.

Activity records can contain:

- Employee who performed the action
- Action performed
- CRM module
- Entity
- Entity ID
- Description
- Timestamp

Activity history supports filtering, sorting, and server-side pagination so relevant records can be retrieved without loading the entire history at once.

## Pagination, Filtering & Search

Supported list and history endpoints use server-side retrieval where applicable.

The APIs support features such as:

- Pagination
- Sorting
- Filtering
- Search

This allows the frontend to request only the records required for a particular view.

## Database

The application uses PostgreSQL as its relational database.

Database persistence is handled through:

- Spring Data JPA
- Hibernate
- JPA entity relationships
- Repository-based data access

The database models relationships between the main CRM entities, including:

- Leads
- Opportunities
- Customers
- Employees
- Follow-ups
- Products
- Activity logs

## External Integrations

### Supabase Storage

Supabase Storage is used for file storage associated with CRM functionality.

The backend handles the relevant storage operations required by the application.

### Brevo

Email functionality is integrated through the Brevo HTTP API.

The backend communicates with Brevo through authenticated REST requests for email delivery.

## REST API

The backend exposes REST APIs for the major CRM modules, including:

- Authentication
- Leads
- Opportunities
- Customers
- Follow-ups
- Employees
- Activity logs
- Reports
- File operations
- Email operations

The APIs support authentication, authorization, business logic, filtering, sorting, and pagination requirements used by the frontend.

## Project Structure

```text
src/
├── main/
│   ├── java/
│   │   └── com/compact/crm/
│   │       ├── controller/
│   │       ├── service/
│   │       ├── repository/
│   │       ├── entity/
│   │       ├── dto/
│   │       ├── security/
│   │       ├── specification/
│   │       └── ...
│   │
│   └── resources/
│       └── application.properties
│
└── test/
```

## Technology Stack

### Backend

- Java
- Spring Boot
- Spring Security
- Spring Data JPA
- Hibernate
- PostgreSQL
- Maven

### Security

- JWT
- Bearer Token Authentication
- Role-Based Access Control
- Scope-Based Authorization

### Integrations

- Supabase Storage
- Brevo API

### Deployment

- Docker
- Render

## Local Development

### Prerequisites

- Java
- Maven
- PostgreSQL

### Build

```bash
mvn clean install
```

### Run

```bash
mvn spring-boot:run
```

The application requires environment-specific configuration for PostgreSQL, JWT, Supabase, Brevo, and other required services.

Sensitive credentials should be supplied through environment variables and should not be committed to the repository.

## Docker

The backend includes Docker configuration for containerized deployment.

The deployed application follows this general architecture:

```text
React Frontend
      |
      | REST API
      v
Spring Boot Backend
      |
      v
PostgreSQL
```

## Deployment

The backend is deployed on Render and provides the REST API consumed by the separately deployed React frontend.

## Frontend

The React frontend for this backend is maintained in a separate repository:

**Compact CRM Frontend**

[Frontend Repository](YOUR_FRONTEND_REPOSITORY_URL)

## Project Status

This backend is part of an actively deployed internal CRM application used by 10+ employees at Compact Systems.

The system continues to evolve as new business requirements and functionality are introduced.
