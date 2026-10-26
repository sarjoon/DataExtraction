import {Component,EventEmitter,Input,Output} from '@angular/core';

export interface ReportGridPage {columns:string[];rows:(string|null)[][];page:number;size:number;hasMore:boolean;message:string|null;}

@Component({selector:'app-report-grid',standalone:true,styleUrl:'./report-grid.css',templateUrl:'./report-grid.html'})
export class ReportGridComponent {
 @Input({required:true}) title='';
 @Input() context='';
 @Input({required:true}) data!:ReportGridPage;
 @Input() clickable=false;
 @Input() linkColumn='';
 @Input() loading=false;
 @Input() hint='Select any row to view its profile details';
 @Input() selectedRow:(string|null)[]|null=null;
 @Output() rowSelect=new EventEmitter<(string|null)[]>();
 @Output() pageChange=new EventEmitter<number>();
 choose(row:(string|null)[]){if(this.clickable&&!this.loading)this.rowSelect.emit(row);}
}
